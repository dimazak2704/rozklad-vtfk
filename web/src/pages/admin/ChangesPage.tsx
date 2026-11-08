import { CalendarPlus, FileText, ListFilter, Plus, WifiOff } from 'lucide-react';
import { useCallback, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useCancelAbsence, useDailyLiveSync, useDashboard, useLessonOptions, type ChangeOption } from '@/api/admin-daily';
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
  filterFromParam,
  isOpen,
  isObjection,
  lessonButtonId,
  LESSON_FILTERS,
  LESSON_STATE,
  matchesFilter,
  optionButtonId,
  optionKey,
  reasonForOption,
  teacherAnswered,
  VISIBLE_OPTIONS,
  type LessonEntry,
  type LessonFilter,
} from '@/components/admin-daily/model';
import { useContainerWidth } from '@/components/admin-daily/useContainerWidth';
import {
  Button,
  buttonClass,
  Chip,
  Drawer,
  DrawerContent,
  EmptyState,
  ErrorState,
  Kbd,
  PageHeader,
  ParityBadge,
  SegmentedControl,
  Skeleton,
  notify,
} from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { cn } from '@/lib/cn';
import { formatDateRange, formatWeekdayDate, formatWeekdayDateAfterDo, isWeekend, shiftStudyDay } from '@/lib/date';
import { useRegisterCommands } from '@/stores/commands';
import { confirm } from '@/stores/confirm';

/** Ширина робочої області, з якої поміщаються дві і три колонки. */
const TWO_PANES = 860;
const THREE_PANES = 1100;
const PULSE_MS = 2_400;

/**
 * Головний щоденний екран диспетчера: відсутні → їхні пари → варіанти рішення → підтвердження.
 * Три колонки на широкому екрані, дві на ноутбуці, на планшеті варіанти відкриваються шторкою.
 */
export default function ChangesPage() {
  const { date, setDate } = useWorkingDate();
  const [params, setParams] = useSearchParams();
  const meta = usePublicMeta();
  const rootRef = useRef<HTMLDivElement>(null);
  const width = useContainerWidth(rootRef);
  const panes = width >= THREE_PANES ? 3 : width >= TWO_PANES ? 2 : 1;

  const period: Period = params.get('period') === 'week' ? 'week' : 'day';
  const filter = filterFromParam(params.get('filter'));
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
  // парність робочої дати (з урахуванням перевизначень тижнів) знає сервер — беремо її з панелі
  const dashboard = useDashboard(date);
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

  const selectedAbsenceId = workspace.absences.some((a) => a.id === absenceParam) ? absenceParam : null;
  const visible = useMemo(
    () =>
      workspace.entries.filter(
        (entry) => (!selectedAbsenceId || entry.absence.id === selectedAbsenceId) && matchesFilter(entry.state, filter),
      ),
    [workspace.entries, selectedAbsenceId, filter],
  );
  const visibleGroups = useMemo(
    () =>
      workspace.groups
        .map((group) => ({
          ...group,
          days: group.days
            .map((day) => ({ ...day, entries: day.entries.filter((entry) => visible.includes(entry)) }))
            .filter((day) => day.entries.length > 0),
        }))
        .filter((group) => group.days.length > 0),
    [workspace.groups, visible],
  );

  // явно обрана пара з адреси; на широкому екрані без вибору — перша, що чекає рішення
  const explicit = lessonParam ? (workspace.entries.find((e) => e.item.lesson.id === lessonParam) ?? null) : null;
  const fallback = panes > 1 ? (visible.find((e) => LESSON_STATE[e.state].needsAction) ?? visible[0] ?? null) : null;
  const selected = explicit ?? fallback;
  const selectedKey = selected ? lessonButtonId(selected) : null;
  const selectedLessonId = selected?.item.lesson.id ?? null;
  const options = useLessonOptions(selected && isOpen(selected.state) ? selected.item.lesson.id : null);
  const optionList = useMemo(() => options.data?.options ?? [], [options.data]);
  const selectedOptionKey = selection && selection.lessonId === selectedLessonId ? selection.key : null;
  const selectedOption = selectedOptionKey ? (optionList.find((o) => optionKey(o) === selectedOptionKey) ?? null) : null;
  const ownReason = selected && reason.lessonId === selectedLessonId ? reason : null;
  const reasonForSelected = {
    code: ownReason?.code ?? reasonForOption(selectedOption?.kind, selected?.absence.reasonCategory),
    text: ownReason?.text ?? '',
  };
  const expanded = expandedFor !== null && expandedFor === selectedLessonId;
  // план дня: скільки пар цього відсутнього того ж дня ще чекають рішення
  const planCount = selected
    ? workspace.entries.filter((e) => e.absence.id === selected.absence.id && e.item.date === selected.item.date && isOpen(e.state)).length
    : 0;

  const selectEntry = useCallback(
    (entry: LessonEntry, focus = false) => {
      updateParams({ lesson: entry.item.lesson.id });
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

  const move = (step: 1 | -1) => {
    if (visible.length === 0) return;
    const index = selected ? visible.indexOf(selected) : -1;
    const next = visible[Math.min(visible.length - 1, Math.max(0, index + step))];
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
    void actions.pick(option, selected, reasonForSelected).then((done) => {
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
    { id: 'only-open', label: 'Показати лише пари без рішення', group: 'Заміни', icon: ListFilter, perform: () => updateParams({ filter: 'action' }) },
    { id: 'order-draft', label: 'Відкрити чернетку розпорядження', group: 'Заміни', icon: FileText, perform: () => void navigate(`/admin/orders?date=${date}`) },
  ]);

  // відповідь викладача в Telegram: тост і короткий «пульс» на рядку пари
  useServerEvent('change.updated', (change) => {
    const last = change.history.at(-1);
    if (!last || !teacherAnswered(last.from, last.to, change.approvalMode)) return;
    if (actions.isOwn(change.id) || change.date < workspace.from || change.date > workspace.to) return;
    const entry = workspace.entries.find((e) => e.item.decision?.changeId === change.id);
    const teacher = change.new.teacher?.shortName ?? 'Викладач';
    const where = `${change.group.code}, ${change.pairFrom} пара`;
    if (isObjection(change)) {
      notify.warning(`${teacher} не може, а заміна вже в розпорядженні`, { description: `${where} — потрібне доповнення` });
    } else if (last.to === 'DECLINED') {
      notify.warning(`Відмова в Telegram: ${teacher}`, { description: `${where} — оберіть інший варіант` });
    } else if (change.approvalMode === 'NOTIFY') {
      notify.info(`${teacher}: «Добре»`, { description: where });
    } else {
      notify.success(`Підтверджено в Telegram: ${teacher}`, { description: where });
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

  const counts = useMemo(() => {
    const scoped = workspace.entries.filter((e) => !selectedAbsenceId || e.absence.id === selectedAbsenceId);
    return Object.fromEntries(LESSON_FILTERS.map((f) => [f.value, scoped.filter((e) => matchesFilter(e.state, f.value)).length])) as Record<
      LessonFilter,
      number
    >;
  }, [workspace.entries, selectedAbsenceId]);

  const weekend = period === 'day' && isWeekend(date);
  const nextStudyDay = shiftStudyDay(date, 1);
  const rangeLabel = period === 'day' ? formatWeekdayDate(date) : `Тиждень: ${formatDateRange(workspace.from, workspace.to)}`;

  const optionsPanel = selected ? (
    <OptionsPanel
      entry={selected}
      approvalMode={approvalMode}
      reasonCode={reasonForSelected.code}
      reasonText={reasonForSelected.text}
      onReasonCodeChange={(code) => setReason({ lessonId: selected.item.lesson.id, code, text: reasonForSelected.text })}
      onReasonTextChange={(text) => setReason({ lessonId: selected.item.lesson.id, code: reasonForSelected.code, text })}
      selectedKey={selectedOptionKey}
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

  const lessonsPane = (
    <section aria-label="Пари, які треба закрити" className="flex min-w-0 flex-col gap-3">
      {panes < 3 && workspace.absences.length > 0 ? (
        <div className="flex flex-col gap-1.5">
          <p className="text-meta font-semibold text-muted">Відсутні</p>
          <AbsenceStrip
            absences={workspace.absences}
            entries={workspace.entries}
            selectedId={selectedAbsenceId}
            onSelect={(id) => updateParams({ absence: id, lesson: null })}
          />
        </div>
      ) : null}
      <div className="flex flex-col gap-1.5">
        {panes < 3 ? <p className="text-meta font-semibold text-muted">Стан пар</p> : null}
        <div role="group" aria-label="Стан пар" className="flex flex-wrap gap-1.5">
          {LESSON_FILTERS.map((f) => (
            <Chip
              key={f.value}
              selected={filter === f.value}
              onToggle={() => updateParams({ filter: f.value === 'all' ? null : f.value })}
              count={counts[f.value]}
            >
              {f.label}
            </Chip>
          ))}
        </div>
      </div>
      {workspace.offline ? (
        <EmptyState
          size="sm"
          icon={<WifiOff aria-hidden="true" className="size-5" />}
          title="Немає звʼязку з сервером"
          description="Дані на цю дату ще не завантажені, а без інтернету їх не отримати. Підключіться до мережі — список оновиться сам."
        />
      ) : workspace.loading && workspace.entries.length === 0 ? (
        <div className="flex flex-col gap-2" aria-hidden="true">
          {[0, 1, 2, 3, 4].map((key) => (
            <Skeleton key={key} className="h-16 w-full rounded-control" />
          ))}
        </div>
      ) : visibleGroups.length === 0 ? (
        <EmptyState
          size="sm"
          title="Немає пар з таким станом"
          description="Змініть фільтр або оберіть інших відсутніх."
          action={
            <Button size="sm" variant="secondary" onClick={() => updateParams({ filter: null, absence: null })}>
              Показати всі пари
            </Button>
          }
        />
      ) : (
        <LessonList
          groups={visibleGroups}
          showDates={period === 'week' || workspace.absences.some((a) => a.dateFrom !== a.dateTo)}
          selectedKey={selectedKey}
          pulsing={pulsing}
          onSelect={(entry) => selectEntry(entry)}
          onEditAbsence={(absence) => openDialog(absence)}
          onCancelAbsence={(absence) => void cancelAbsenceFlow(absence)}
        />
      )}
    </section>
  );

  let body;
  if (workspace.error && workspace.absences.length === 0) {
    body = <ErrorState error={workspace.error} onRetry={workspace.retry} />;
  } else if (weekend) {
    body = (
      <EmptyState
        title={`${formatWeekdayDate(date)} — вихідний`}
        description="Пар цього дня немає. Відсутності на вихідні не потрібні — перейдіть до найближчого навчального дня."
        action={
          <Button variant="primary" onClick={() => setDate(nextStudyDay)}>
            До {formatWeekdayDateAfterDo(nextStudyDay)}
          </Button>
        }
        secondaryAction={
          <Button variant="secondary" onClick={() => updateParams({ period: 'week' })}>
            Показати тиждень
          </Button>
        }
      />
    );
  } else if (!workspace.loading && workspace.absences.length === 0) {
    body = (
      <EmptyState
        title={period === 'day' ? 'Відсутніх немає' : 'Цього тижня відсутніх немає'}
        description="Коли викладач повідомить про відсутність — через бота, кабінет чи телефоном, — його пари зʼявляться тут разом з варіантами заміни."
        action={
          <Button variant="primary" onClick={openNewAbsence} iconStart={<Plus aria-hidden="true" className="size-4" />}>
            Нова відсутність
          </Button>
        }
      />
    );
  } else {
    body = (
      <div
        className={cn(
          'grid items-start gap-4',
          panes === 3 && 'grid-cols-[15.5rem_minmax(0,1fr)_minmax(0,1.2fr)]',
          panes === 2 && 'grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]',
        )}
      >
        {panes === 3 ? (
          <aside aria-label="Відсутні" className="sticky top-[calc(var(--sticky-top)+1rem)] flex flex-col gap-2">
            <h2 className="px-1 text-heading font-semibold text-primary">Відсутні</h2>
            <AbsenceList
              absences={workspace.absences}
              entries={workspace.entries}
              selectedId={selectedAbsenceId}
              onSelect={(id) => updateParams({ absence: id, lesson: null })}
              loading={workspace.loading}
            />
          </aside>
        ) : null}
        <div className="min-w-0 rounded-panel border border-default bg-surface p-3">{lessonsPane}</div>
        {panes > 1 ? (
          <aside
            aria-label="Варіанти рішення"
            className="sticky top-[calc(var(--sticky-top)+1rem)] max-h-[calc(100dvh-var(--sticky-top)-2rem)] min-w-0 overflow-y-auto overscroll-contain rounded-panel border border-default bg-surface p-4"
          >
            {optionsPanel ?? (
              <EmptyState
                size="sm"
                title="Оберіть пару"
                description="Ліворуч — пари відсутніх. Оберіть одну, щоб побачити варіанти: інша дисципліна групи, перестановка чи скасування."
              />
            )}
          </aside>
        ) : null}
      </div>
    );
  }

  return (
    <div ref={rootRef} className="flex flex-col gap-5">
      <PageHeader
        title="Заміни"
        description="Відсутні викладачі, їхні пари і найкращі варіанти заміни з підтвердженням у Telegram."
        meta={
          <>
            <span className="text-ui font-semibold text-primary">{rangeLabel}</span>
            {/* панель попередньої дати (keepPreviousData) може мати іншу парність — показуємо лише свою */}
            {dashboard.data && !dashboard.isPlaceholderData && period === 'day' && !weekend ? (
              <ParityBadge parity={dashboard.data.parity} size="sm" />
            ) : null}
            {counts.action > 0 ? (
              <span className="text-caption font-semibold text-danger">{counts.action} без рішення</span>
            ) : workspace.entries.length > 0 ? (
              <span className="text-caption font-semibold text-add">Усі пари вирішено</span>
            ) : null}
          </>
        }
        actions={
          <>
            <SegmentedControl<Period>
              aria-label="Період"
              size="sm"
              value={period}
              onValueChange={(value) => updateParams({ period: value === 'day' ? null : value, lesson: null })}
              options={[
                { value: 'day', label: 'День' },
                { value: 'week', label: 'Тиждень' },
              ]}
            />
            <Link to={`/admin/orders?date=${date}`} className={buttonClass({ variant: 'secondary' })}>
              <FileText aria-hidden="true" className="size-4" />
              Розпорядження
            </Link>
            <Button variant="secondary" onClick={openNewAbsence} iconStart={<Plus aria-hidden="true" className="size-4" />}>
              Нова відсутність
              <span aria-hidden="true" className="ms-1 hidden xl:inline-flex">
                <Kbd>N</Kbd>
              </span>
            </Button>
          </>
        }
      />

      {body}

      {panes === 1 ? (
        <Drawer direction="right" open={Boolean(explicit)} onOpenChange={(open) => !open && updateParams({ lesson: null })}>
          <DrawerContent side="right" title="Варіанти рішення" description={explicit ? `${explicit.item.lesson.group.code}, ${explicit.item.lesson.pair} пара` : undefined}>
            {optionsPanel}
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
          updateParams({ absence: absence.id, lesson: null, filter: null });
          notify.success(created ? `Відсутність додано: ${absence.teacher.shortName}` : `Відсутність змінено: ${absence.teacher.shortName}`, {
            description: absencePeriod(absence),
          });
        }}
      />
    </div>
  );
}
