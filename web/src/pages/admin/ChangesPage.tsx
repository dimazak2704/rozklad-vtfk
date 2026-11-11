import { CalendarPlus, ChevronLeft, FileText, Plus } from 'lucide-react';
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useCancelAbsence, useDailyLiveSync, useLessonOptions, type ChangeOption } from '@/api/admin-daily';
import { useServerEvent } from '@/api/live';
import { usePublicMeta } from '@/api/meta';
import { useSettings } from '@/api/settings';
import type { AbsenceDto, ReasonCode, Uuid } from '@/api/types';
import { AbsenceDialog } from '@/components/admin-daily/changes/AbsenceDialog';
import { AbsenceList } from '@/components/admin-daily/changes/AbsenceList';
import { AbsenceStrip } from '@/components/admin-daily/changes/AbsenceStrip';
import { LessonList } from '@/components/admin-daily/changes/LessonList';
import { OptionsPanel } from '@/components/admin-daily/changes/OptionsPanel';
import { useChangesWorkspace, type Period } from '@/components/admin-daily/changes/useChangesWorkspace';
import { useDecisionActions } from '@/components/admin-daily/changes/useDecisionActions';
import { absencePeriod } from '@/components/admin-daily/format';
import {
  effectiveOptionKey,
  isObjection,
  isOpen,
  lessonButtonId,
  LESSON_STATE,
  optionButtonId,
  optionKey,
  reasonForOption,
  teacherAnswered,
  VISIBLE_OPTIONS,
  type LessonEntry,
} from '@/components/admin-daily/model';
import { OfflineState } from '@/components/admin-daily/OfflineState';
import { ResolvedProgress } from '@/components/admin-daily/ResolvedProgress';
import { useContainerWidth } from '@/components/admin-daily/useContainerWidth';
import {
  Button,
  buttonClass,
  Drawer,
  DrawerContent,
  EmptyState,
  PageErrorBanner,
  PageHeader,
  SegmentedControl,
  Skeleton,
  notify,
} from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { cn } from '@/lib/cn';
import { formatWeekdayDate, formatWeekdayDateAfterDo, formatWeekdayDateAfterNa, isWeekend, shiftStudyDay } from '@/lib/date';
import { useRegisterCommands } from '@/stores/commands';
import { confirm } from '@/stores/confirm';

/** Ширина робочої області, з якої поміщаються дві і три колонки. */
const TWO_PANES = 760;
const THREE_PANES = 1080;
const PULSE_MS = 2_400;

const STICKY = 'lg:sticky lg:top-[calc(var(--sticky-top)+1rem)] lg:max-h-[calc(100dvh-var(--sticky-top)-2rem)] lg:overflow-y-auto lg:overscroll-contain';

function WorkspaceSkeleton({ columns }: { columns: number }) {
  return (
    <div aria-hidden="true" className={cn('grid gap-4 p-4', columns === 3 && 'grid-cols-[28fr_32fr_40fr]', columns === 2 && 'grid-cols-2')}>
      {Array.from({ length: columns }, (_, column) => (
        <div key={column} className="flex flex-col gap-3">
          {[0, 1, 2].map((key) => (
            <Skeleton key={key} className="h-14 w-full rounded-popover" />
          ))}
        </div>
      ))}
    </div>
  );
}

/**
 * Головний щоденний екран диспетчера: відсутні → їхні пари → варіанти рішення → підтвердження.
 * Широкий екран — одна панель з трьома колонками; ноутбук — відсутні рядком над двома колонками;
 * телефон і планшет — по рівнях: відсутні → пари → варіанти в шторці, з дорогою назад на кожному.
 */
export default function ChangesPage() {
  const { date, setDate } = useWorkingDate();
  const [params, setParams] = useSearchParams();
  const meta = usePublicMeta();
  const rootRef = useRef<HTMLDivElement>(null);
  const width = useContainerWidth(rootRef);
  const panes = width >= THREE_PANES ? 3 : width >= TWO_PANES ? 2 : 1;
  const narrow = !useMediaQuery('(min-width: 640px)', true);

  const period: Period = params.get('period') === 'week' ? 'week' : 'day';
  const absenceParam = params.get('absence');
  const lessonParam = params.get('lesson');

  const updateParams = useCallback(
    (patch: Record<string, string | null>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(patch)) {
            if (value === null) next.delete(key);
            else next.set(key, value);
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  useDailyLiveSync();
  const navigate = useNavigate();
  const workspace = useChangesWorkspace(date, period);
  const actions = useDecisionActions();
  const cancelAbsence = useCancelAbsence();
  // напис дії залежить від режиму погодження; будь-який інший за CONFIRM — «Призначити»
  const approvalMode = useSettings().data?.approvalMode ?? null;
  const [dialog, setDialog] = useState<{ open: boolean; absence: AbsenceDto | null; seq: number }>({ open: false, absence: null, seq: 0 });
  const [pulsing, setPulsing] = useState<ReadonlySet<Uuid>>(new Set());
  // причину диспетчер може змінити сам (code), інакше вона йде за видом обраного варіанта
  const [reason, setReason] = useState<{ lessonId: string | null; code: ReasonCode | null; text: string }>({
    lessonId: null,
    code: null,
    text: '',
  });
  // обраний варіант і розгорнутий список — лише для своєї пари: інша пара починає з чистого вибору
  const [selection, setSelection] = useState<{ lessonId: Uuid; key: string } | null>(null);
  const [expandedFor, setExpandedFor] = useState<Uuid | null>(null);

  const explicit = lessonParam ? (workspace.entries.find((e) => e.item.lesson.id === lessonParam) ?? null) : null;
  // відсутній: з адреси → відсутній обраної пари → на широкому екрані перший, у кого є що вирішувати
  const explicitAbsence = workspace.absences.find((a) => a.id === absenceParam) ?? explicit?.absence ?? null;
  const fallbackAbsence =
    panes > 1
      ? (workspace.entries.find((e) => LESSON_STATE[e.state].needsAction)?.absence ?? workspace.absences[0] ?? null)
      : null;
  const selectedAbsence = explicitAbsence ?? fallbackAbsence;
  const selectedAbsenceId = selectedAbsence?.id ?? null;
  const group = workspace.groups.find((g) => g.absence.id === selectedAbsenceId) ?? null;
  const visible = selectedAbsenceId ? workspace.entries.filter((entry) => entry.absence.id === selectedAbsenceId) : workspace.entries;

  // явно обрана пара з адреси; на широкому екрані без вибору — перша, що чекає рішення
  const fallback = panes > 1 ? (visible.find((e) => LESSON_STATE[e.state].needsAction) ?? visible[0] ?? null) : null;
  const selected = explicit ?? fallback;
  const selectedKey = selected ? lessonButtonId(selected) : null;
  const selectedLessonId = selected?.item.lesson.id ?? null;
  const options = useLessonOptions(selected && isOpen(selected.state) ? selected.item.lesson.id : null);
  const optionList = useMemo(() => options.data?.options ?? [], [options.data]);
  const explicitOptionKey = selection && selection.lessonId === selectedLessonId ? selection.key : null;
  const selectedOptionKey = effectiveOptionKey(optionList, explicitOptionKey);
  const selectedOption = selectedOptionKey ? (optionList.find((o) => optionKey(o) === selectedOptionKey) ?? null) : null;
  const ownReason = selected && reason.lessonId === selectedLessonId ? reason : null;
  const reasonFor = (option: ChangeOption | null) => ({
    code: ownReason?.code ?? reasonForOption(option?.kind, selected?.absence.reasonCategory),
    text: ownReason?.text ?? '',
  });
  const reasonForSelected = reasonFor(selectedOption);
  const expanded = expandedFor !== null && expandedFor === selectedLessonId;
  // план дня: скільки пар цього відсутнього того ж дня ще чекають рішення
  const planCount = selected
    ? workspace.entries.filter((e) => e.absence.id === selected.absence.id && e.item.date === selected.item.date && isOpen(e.state)).length
    : 0;

  const selectEntry = useCallback(
    (entry: LessonEntry, focus = false) => {
      // пара іншого відсутнього (J/K на межі списку) — перемикаємо й відсутнього
      updateParams({ lesson: entry.item.lesson.id, absence: entry.absence.id });
      if (focus) {
        requestAnimationFrame(() => {
          const button = document.getElementById(lessonButtonId(entry));
          button?.focus({ preventScroll: true });
          button?.scrollIntoView({ block: 'nearest' });
        });
      }
    },
    [updateParams],
  );

  const selectAbsence = (id: Uuid | null) => updateParams({ absence: id, lesson: null });

  /** J/K — наступна чи попередня пара; на межі списку переходить до пар сусіднього відсутнього. */
  const move = (step: 1 | -1) => {
    // у тому ж порядку, що й на екрані: відсутній за відсутнім, усередині — за датою
    const all = workspace.groups.flatMap((g) => g.days.flatMap((d) => d.entries));
    if (all.length === 0) return;
    const index = selected ? all.indexOf(selected) : -1;
    const next = all[Math.min(all.length - 1, Math.max(0, index + step))];
    if (next) selectEntry(next, true);
  };

  const selectOption = (option: ChangeOption) => {
    if (!selected) return;
    setSelection({ lessonId: selected.item.lesson.id, key: optionKey(option) });
  };

  const act = (option: ChangeOption) => {
    if (!selected) return;
    // закріплюємо пару в адресі: після рішення диспетчер бачить його й строк, а не наступну пару
    updateParams({ lesson: selected.item.lesson.id });
    void actions.pick(option, selected, reasonFor(option)).then((done) => {
      if (done) setSelection(null);
    });
  };

  /** 1–9 лише обирають варіант (і переводять на нього фокус); дія — Enter чи кнопка в рядку. */
  const selectNumber = (n: number) => {
    if (!selected || !isOpen(selected.state)) return;
    const option = optionList[n - 1];
    if (!option) return;
    if (n > VISIBLE_OPTIONS) setExpandedFor(selected.item.lesson.id);
    selectOption(option);
    const id = optionButtonId(selected.item.lesson.id, n - 1);
    requestAnimationFrame(() => {
      const button = document.getElementById(id);
      button?.focus({ preventScroll: true });
      button?.scrollIntoView({ block: 'nearest' });
    });
  };

  const openDialog = (absence: AbsenceDto | null) => setDialog((prev) => ({ open: true, absence, seq: prev.seq + 1 }));
  const openNewAbsence = () => openDialog(null);

  useHotkeys([
    { keys: 'j', handler: () => move(1), description: 'Наступна пара', group: 'Заміни' },
    { keys: 'k', handler: () => move(-1), description: 'Попередня пара', group: 'Заміни' },
    ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => ({
      keys: String(n),
      handler: () => selectNumber(n),
      description: n === 1 ? 'Обрати варіант 1–9 (дія — Enter на обраному)' : undefined,
      group: 'Заміни',
    })),
    { keys: 'n', handler: openNewAbsence, description: 'Нова відсутність', group: 'Заміни' },
  ]);

  useRegisterCommands([
    { id: 'new-absence', label: 'Нова відсутність', group: 'Заміни', icon: CalendarPlus, shortcut: 'n', perform: openNewAbsence, keywords: ['лікарняний', 'відсутній'] },
    { id: 'order-draft', label: 'Відкрити чернетку розпорядження', group: 'Заміни', icon: FileText, perform: () => void navigate(`/admin/orders?date=${date}`) },
  ]);

  // відповідь викладача в Telegram: тост одним реченням і короткий «пульс» на рядку пари
  useServerEvent('change.updated', (change) => {
    const last = change.history.at(-1);
    if (!last || !teacherAnswered(last.from, last.to, change.approvalMode)) return;
    if (actions.isOwn(change.id) || change.date < workspace.from || change.date > workspace.to) return;
    const entry = workspace.entries.find((e) => e.item.decision?.changeId === change.id);
    const teacher = change.new.teacher?.shortName ?? 'Викладач';
    const where = `${change.group.code}, ${change.pairFrom} пара`;
    if (isObjection(change)) {
      notify.warning(`${teacher} не може (${where}), а заміна вже в розпорядженні — потрібне доповнення`);
    } else if (last.to === 'DECLINED') {
      notify.warning(`${teacher} відмовився (${where}) — оберіть інший варіант`);
    } else if (change.approvalMode === 'NOTIFY') {
      notify.info(`${teacher}: «Добре» (${where})`);
    } else {
      notify.success(`Підтверджено в Telegram: ${teacher} (${where})`);
    }
    if (entry) {
      const lessonId = entry.item.lesson.id;
      setPulsing((prev) => new Set(prev).add(lessonId));
      window.setTimeout(() => {
        setPulsing((prev) => {
          const next = new Set(prev);
          next.delete(lessonId);
          return next;
        });
      }, PULSE_MS);
    }
  });

  const cancelAbsenceFlow = async (absence: AbsenceDto) => {
    const ok = await confirm({
      title: 'Викладач на місці — скасувати відсутність?',
      description: 'Рішення за цією відсутністю, ще не внесені в розпорядження, теж скасуються.',
      objectName: `${absence.teacher.shortName}, ${absencePeriod(absence)}`,
      confirmLabel: 'Скасувати відсутність',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await cancelAbsence.mutateAsync(absence.id);
      if (selectedAbsenceId === absence.id) updateParams({ absence: null, lesson: null });
      notify.success(`Відсутність скасовано: ${absence.teacher.shortName}`);
    } catch (error) {
      notify.error(error, 'Не вдалося скасувати відсутність');
    }
  };

  const done = workspace.entries.filter((e) => !LESSON_STATE[e.state].needsAction).length;
  const weekend = period === 'day' && isWeekend(date);
  const nextStudyDay = shiftStudyDay(date, 1);

  const optionsPanel = (showTitle: boolean) =>
    selected ? (
      <OptionsPanel
        entry={selected}
        showTitle={showTitle}
        approvalMode={approvalMode}
        reasonCode={reasonForSelected.code}
        reasonText={reasonForSelected.text}
        onReasonCodeChange={(code) => setReason({ lessonId: selected.item.lesson.id, code, text: reasonForSelected.text })}
        onReasonTextChange={(text) => setReason({ lessonId: selected.item.lesson.id, code: reasonForSelected.code, text })}
        selectedKey={explicitOptionKey}
        onSelect={selectOption}
        expanded={expanded}
        onExpandedChange={(next) => setExpandedFor(next ? selected.item.lesson.id : null)}
        pickingKey={actions.pickingKey}
        onAct={act}
        onAssignManual={(teacher) => {
          updateParams({ lesson: selected.item.lesson.id });
          return actions.assignManual(teacher, selected, reasonForSelected);
        }}
        planCount={planCount}
        onApplyPlan={(choices) => actions.applyPlan(choices, selected.absence, selected.item.date)}
        decisionBusy={actions.busy}
        onResend={() => selected.change && actions.resend(selected.change)}
        onConfirm={() => selected.change && actions.confirm(selected.change)}
        onCancelDecision={() => selected.change && actions.cancelDecision(selected.change, selected)}
      />
    ) : null;

  const showDates = period === 'week' || workspace.absences.some((a) => a.dateFrom !== a.dateTo);
  const lessons = (before?: ReactNode) =>
    group ? (
      <LessonList
        group={group}
        showDates={showDates}
        selectedKey={selectedKey}
        pulsing={pulsing}
        onSelect={(entry) => selectEntry(entry)}
        onEditAbsence={(absence) => openDialog(absence)}
        onCancelAbsence={(absence) => void cancelAbsenceFlow(absence)}
        before={before}
      />
    ) : null;

  const absenceList = (drill: boolean) => (
    <AbsenceList
      absences={workspace.absences}
      entries={workspace.entries}
      selectedId={selectedAbsenceId}
      onSelect={(id) => selectAbsence(id)}
      onAdd={openNewAbsence}
      loading={workspace.loading}
      drill={drill}
    />
  );

  let body;
  if (workspace.error && workspace.absences.length === 0) {
    body = <PageErrorBanner error={workspace.error} title="Не вдалося завантажити відсутності" onRetry={workspace.retry} retrying={workspace.loading} />;
  } else if (weekend) {
    body = (
      <EmptyState
        title={`${formatWeekdayDate(date)} — вихідний, пар немає.`}
        description="Відсутності на вихідні не потрібні."
        action={
          <Button variant="secondary" onClick={() => setDate(nextStudyDay)}>
            До {formatWeekdayDateAfterDo(nextStudyDay)}
          </Button>
        }
        secondaryAction={
          <Button variant="ghost" onClick={() => updateParams({ period: 'week' })}>
            Показати тиждень
          </Button>
        }
      />
    );
  } else if (workspace.offline) {
    body = <OfflineState />;
  } else if (!workspace.loading && workspace.absences.length === 0) {
    body = (
      <EmptyState
        title={period === 'day' ? `На ${formatWeekdayDateAfterNa(date)} ніхто не відсутній.` : 'Цього тижня ніхто не відсутній.'}
        description="Коли викладач повідомить про відсутність через бота чи кабінет, його пари зʼявляться тут разом з варіантами заміни."
        action={
          <Button variant="ghost" className="-ms-3" onClick={openNewAbsence} iconStart={<Plus aria-hidden="true" className="size-4" />}>
            Додати відсутність
          </Button>
        }
      />
    );
  } else if (workspace.loading && workspace.entries.length === 0) {
    body = (
      <div className="rounded-panel border border-default bg-surface">
        <WorkspaceSkeleton columns={panes} />
      </div>
    );
  } else if (panes === 3) {
    body = (
      <div className="grid grid-cols-[28fr_32fr_40fr] items-stretch rounded-panel border border-default bg-surface">
        <section aria-labelledby="absent-heading" className="flex min-w-0 flex-col gap-2 p-3">
          <h2 id="absent-heading" className="px-3 pt-2 text-ui font-[650] text-secondary">
            Відсутні
          </h2>
          {absenceList(false)}
        </section>
        <div className="min-w-0 border-s border-separator p-3 pt-5">{lessons()}</div>
        <aside aria-label="Рішення" className="min-w-0 border-s border-separator">
          <div className={cn('p-5', STICKY)}>{optionsPanel(true)}</div>
        </aside>
      </div>
    );
  } else if (panes === 2) {
    body = (
      <div className="flex flex-col gap-4">
        <AbsenceStrip
          absences={workspace.absences}
          entries={workspace.entries}
          selectedId={selectedAbsenceId}
          onSelect={(id) => selectAbsence(id)}
          onAdd={openNewAbsence}
        />
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] items-stretch rounded-panel border border-default bg-surface">
          <div className="min-w-0 p-3 pt-5">{lessons()}</div>
          <aside aria-label="Рішення" className="min-w-0 border-s border-separator">
            <div className={cn('p-5', STICKY)}>{optionsPanel(true)}</div>
          </aside>
        </div>
      </div>
    );
  } else {
    // телефон і вузький планшет: по рівнях, і з кожного видно дорогу назад
    body = (
      <div className="rounded-panel border border-default bg-surface p-2 pt-3">
        {group
          ? lessons(
              <button
                type="button"
                onClick={() => selectAbsence(null)}
                className="ms-1 inline-flex min-h-9 w-fit items-center gap-0.5 rounded-control pe-2 text-ui font-[550] text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
              >
                <ChevronLeft aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
                Усі відсутні
              </button>,
            )
          : absenceList(true)}
      </div>
    );
  }

  return (
    <div ref={rootRef} className="flex flex-col gap-6">
      <PageHeader
        title="Заміни"
        meta={workspace.entries.length > 0 && !weekend ? <ResolvedProgress done={done} total={workspace.entries.length} /> : null}
        controls={
          <>
            <SegmentedControl<Period>
              aria-label="Період"
              value={period}
              onValueChange={(value) => updateParams({ period: value === 'day' ? null : value, lesson: null })}
              options={[
                { value: 'day', label: 'День' },
                { value: 'week', label: 'Тиждень' },
              ]}
            />
            <Link to={`/admin/orders?date=${date}`} className={buttonClass({ variant: 'secondary', className: 'max-sm:hidden' })}>
              <FileText aria-hidden="true" className="size-4" />
              Розпорядження
            </Link>
          </>
        }
        // `⋯` тримає лише те, чого на екрані немає: «Додати відсутність» видно в списку відсутніх, тож у меню
        // вона потрібна тільки на телефоні на рівні пар; кнопка «Розпорядження» ховається на вузькому
        more={[
          ...(panes === 1 && group && !weekend
            ? [{ label: 'Нова відсутність', icon: <CalendarPlus aria-hidden="true" />, onSelect: openNewAbsence }]
            : []),
          ...(narrow ? [{ label: 'Розпорядження', icon: <FileText aria-hidden="true" />, to: `/admin/orders?date=${date}` }] : []),
        ]}
      />

      {body}

      {panes === 1 ? (
        <Drawer direction="right" open={Boolean(explicit)} onOpenChange={(open) => !open && updateParams({ lesson: null })}>
          <DrawerContent side="right" title={explicit ? `${explicit.item.lesson.group.code}, ${explicit.item.lesson.pair} пара` : 'Варіанти'}>
            {optionsPanel(false)}
          </DrawerContent>
        </Drawer>
      ) : null}

      <AbsenceDialog
        key={dialog.seq}
        open={dialog.open}
        onOpenChange={(open) => setDialog((prev) => ({ ...prev, open }))}
        date={date}
        absence={dialog.absence}
        semester={meta.data?.semester ?? null}
        onSaved={(absence, created) => {
          updateParams({ absence: absence.id, lesson: null });
          notify.success(`${created ? 'Відсутність додано' : 'Відсутність змінено'}: ${absence.teacher.shortName}, ${absencePeriod(absence)}`);
        }}
      />
    </div>
  );
}
