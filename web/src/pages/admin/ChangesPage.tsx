import { CalendarPlus, ChevronLeft, FilePlus2, FileText, Plus } from 'lucide-react';
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { qk } from '@/api/queryKeys';
import { useCancelAbsence, useCancelChange, useDailyLiveSync, useUpdateAbsence, useLessonOptions, type ChangeOption } from '@/api/admin-daily';
import { useServerEvent } from '@/api/live';
import { usePublicMeta } from '@/api/meta';
import { useSettings } from '@/api/settings';
import type { AbsenceDto, AbsenceRequest, ChangeDto, ReasonCode, Uuid } from '@/api/types';
import { AbsenceDialog } from '@/components/admin-daily/changes/AbsenceDialog';
import { AbsenceList } from '@/components/admin-daily/changes/AbsenceList';
import { AbsenceStrip } from '@/components/admin-daily/changes/AbsenceStrip';
import { LessonList } from '@/components/admin-daily/changes/LessonList';
import { prefillFromParams, standaloneChanges, standaloneHeading, type NewChangePrefill } from '@/components/admin-daily/changes/new-change';
import { NewChangeDialog } from '@/components/admin-daily/changes/NewChangeDialog';
import { StandaloneChanges } from '@/components/admin-daily/changes/StandaloneChanges';
import { OptionsPanel } from '@/components/admin-daily/changes/OptionsPanel';
import { useChangesWorkspace, type Period } from '@/components/admin-daily/changes/useChangesWorkspace';
import { useDecisionActions } from '@/components/admin-daily/changes/useDecisionActions';
import { absencePeriod, decisionSummary } from '@/components/admin-daily/format';
import {
  answerToast,
  effectiveOptionKey,
  isOpen,
  lessonButtonId,
  LESSON_STATE,
  optionButtonId,
  optionKey,
  reasonForOption,
  tallyEntries,
  teacherAnswered,
  VISIBLE_OPTIONS,
  type LessonEntry,
} from '@/components/admin-daily/model';
import { OfflineState } from '@/components/admin-daily/OfflineState';
import { ResolvedProgress } from '@/components/admin-daily/ResolvedProgress';
import { useContainerWidth } from '@/components/admin-daily/useContainerWidth';
import { useTeacherLabel } from '@/components/admin-daily/useTeacherLabel';
import {
  Button,
  buttonClass,
  Drawer,
  DrawerContent,
  EmptyState,
  LoadingRegion,
  PageErrorBanner,
  PageHeader,
  SegmentedControl,
  Skeleton,
  Tooltip,
  notify,
} from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { usePulse } from '@/hooks/usePulse';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { cn } from '@/lib/cn';
import { formatWeekdayDate, formatWeekdayDateAfterDo, formatWeekdayDateAfterNa, isWeekend, shiftStudyDay } from '@/lib/date';
import { useRegisterCommands } from '@/stores/commands';
import { confirm } from '@/stores/confirm';
import { track, trackFinal } from '@/lib/undo/actions';

/** Ширина робочої області, з якої поміщаються дві і три колонки. */
const TWO_PANES = 760;
const THREE_PANES = 1080;
const PULSE_MS = 2_400;

/**
 * Колонка варіантів тримається під верхньою панеллю, а прокручується всередині себе. Смуга прокрутки відступає
 * від заокругленого кута панелі (my/me), місце під неї зарезервоване (без стрибка ширини), а низ тихо згасає —
 * видно, що далі є ще варіанти.
 */
const STICKY = 'lg:sticky lg:top-[calc(var(--sticky-top)+1rem)] lg:my-2.5 lg:me-1.5';
const STICKY_SCROLL =
  'lg:max-h-[calc(100dvh-var(--sticky-top)-2.5rem)] lg:overflow-y-auto lg:overscroll-contain lg:[scrollbar-gutter:stable] lg:[scrollbar-width:thin] ' +
  // slop-allow: маска прозорості (не колір): низ колонки тихо згасає, показуючи, що далі є ще варіанти
  'lg:pb-8 lg:[mask-image:linear-gradient(to_bottom,#000_calc(100%-1.5rem),transparent)]';

/** Рядок відсутнього в силуеті: імʼя, підпис і смужка «вирішено». */
function AbsenceRowSkeleton() {
  return (
    <div className="flex flex-col gap-2 px-3 py-2.5">
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-3 w-1/2" />
      <Skeleton className="h-2.5 w-28" />
    </div>
  );
}

/** Рядок пари в силуеті: час ліворуч, група з дисципліною й стан праворуч. */
function LessonRowSkeleton() {
  return (
    <div className="grid grid-cols-[3.25rem_minmax(0,1fr)] gap-x-3 px-3 py-2.5">
      <div className="flex flex-col gap-1.5">
        <Skeleton className="h-4 w-11" />
        <Skeleton className="h-3 w-9" />
      </div>
      <div className="flex flex-col gap-1.5">
        <Skeleton className="h-4 w-3/4" />
        <Skeleton className="h-3 w-1/2" />
      </div>
    </div>
  );
}

/** Силует колонки варіантів: заголовок, підпис і картка найкращого варіанта. */
function OptionsSkeleton() {
  return (
    <div className="flex flex-col gap-3 p-5">
      <Skeleton className="h-5 w-2/3" />
      <Skeleton className="h-3 w-1/2" />
      <Skeleton className="mt-2 h-36 w-full rounded-popover" />
      <Skeleton className="h-12 w-full rounded-popover" />
    </div>
  );
}

/**
 * Силует тієї самої форми, що й робоча панель (відсутні · пари · варіанти; на ноутбуці — рядок відсутніх над
 * двома колонками; на телефоні — список): поки не прийшли всі запити, панель не показується наполовину.
 */
function WorkspaceSkeleton({ panes }: { panes: number }) {
  const lessons = (
    <div className="flex flex-col gap-1 p-3 pt-5">
      <div className="flex flex-col gap-1.5 px-3 pb-2">
        <Skeleton className="h-5 w-1/2" />
        <Skeleton className="h-3 w-2/3" />
      </div>
      {[0, 1, 2].map((key) => (
        <LessonRowSkeleton key={key} />
      ))}
    </div>
  );
  if (panes === 3) {
    return (
      <div aria-hidden="true" className="grid grid-cols-[28fr_32fr_40fr] rounded-panel border border-default bg-surface">
        <div className="flex flex-col gap-1 p-3">
          <Skeleton className="mx-3 mt-2 mb-1 h-4 w-20" />
          {[0, 1, 2, 3].map((key) => (
            <AbsenceRowSkeleton key={key} />
          ))}
        </div>
        <div className="border-s border-separator">{lessons}</div>
        <div className="border-s border-separator">
          <OptionsSkeleton />
        </div>
      </div>
    );
  }
  if (panes === 2) {
    return (
      <div aria-hidden="true" className="flex flex-col gap-4">
        <div className="flex gap-1.5">
          {[0, 1, 2, 3].map((key) => (
            <Skeleton key={key} className="h-9 w-28 rounded-full" />
          ))}
        </div>
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] rounded-panel border border-default bg-surface">
          {lessons}
          <div className="border-s border-separator">
            <OptionsSkeleton />
          </div>
        </div>
      </div>
    );
  }
  return (
    <div aria-hidden="true" className="rounded-panel border border-default bg-surface p-2 pt-3">
      {[0, 1, 2, 3].map((key) => (
        <AbsenceRowSkeleton key={key} />
      ))}
    </div>
  );
}

/**
 * Головний щоденний екран диспетчера: відсутні → їхні пари → варіанти рішення → підтвердження.
 * Широкий екран — одна панель з трьома колонками; ноутбук — відсутні рядком над двома колонками;
 * телефон і планшет — по рівнях: відсутні → пари → варіанти в шторці, з дорогою назад на кожному.
 */
/** Відсутність → тіло PUT (без версії: її додає журнал). */
function absenceRequest(absence: AbsenceDto): AbsenceRequest {
  return {
    teacherId: absence.teacher.id,
    dateFrom: absence.dateFrom,
    dateTo: absence.dateTo,
    pairs: absence.pairs,
    reasonCategory: absence.reasonCategory,
    comment: absence.comment,
  };
}

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
  const name = useTeacherLabel();
  const workspace = useChangesWorkspace(date, period);
  const actions = useDecisionActions();
  const cancelAbsence = useCancelAbsence();
  const updateAbsence = useUpdateAbsence();
  // напис дії залежить від режиму погодження; будь-який інший за CONFIRM — «Призначити»
  const approvalMode = useSettings().data?.approvalMode ?? null;
  const [dialog, setDialog] = useState<{
    open: boolean;
    absence: AbsenceDto | null;
    seq: number;
  }>({ open: false, absence: null, seq: 0 });
  const [changeDialog, setChangeDialog] = useState<{
    open: boolean;
    prefill: NewChangePrefill | null;
    seq: number;
  }>({ open: false, prefill: null, seq: 0 });
  const cancelChange = useCancelChange();
  // з «Обліку годин» («Додати 2 пари», «Оформити «Вичит.»») — одразу форма зміни для цієї групи й дисципліни,
  // а не перша-ліпша відсутність
  const urlPrefill = prefillFromParams(params);
  const [pulsing, pulse] = usePulse<Uuid>(PULSE_MS);
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
  // поки пари їдуть, не вгадуємо: інакше виділення перескочило б з першого на того, у кого є що вирішувати
  const fallbackAbsence =
    panes > 1 && !workspace.loading
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
  const openChangeWith = (prefill: NewChangePrefill | null) =>
    setChangeDialog((prev) => ({
      open: true,
      prefill,
      seq: prev.seq + 1,
    }));
  // без аргументів: кнопки й гарячі клавіші передають сюди подію, а не заготовку форми
  const openNewChange = () => openChangeWith(null);
  const closeNewChange = () => {
    setChangeDialog((prev) => ({ ...prev, open: false }));
    if (urlPrefill) updateParams({ group: null, discipline: null, suggest: null, lessons: null });
  };

  useHotkeys([
    { keys: 'j', handler: () => move(1), description: 'Наступна пара', group: 'Заміни' },
    { keys: 'k', handler: () => move(-1), description: 'Попередня пара', group: 'Заміни' },
    ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => ({
      keys: String(n),
      handler: () => selectNumber(n),
      description: n === 1 ? 'Обрати варіант за номером (дія — Enter на обраному)' : undefined,
      helpKeys: n === 1 ? '1–9' : undefined,
      group: 'Заміни',
    })),
    {
      keys: 'n',
      handler: openNewAbsence,
      description: 'Нова відсутність',
      group: 'Заміни',
    },
    {
      keys: 'c',
      handler: openNewChange,
      description: 'Нова зміна без відсутності (додаткова пара, аудиторія…)',
      group: 'Заміни',
    },
  ]);

  useRegisterCommands([
    {
      id: 'new-absence',
      label: 'Нова відсутність',
      group: 'Заміни',
      icon: CalendarPlus,
      shortcut: 'n',
      perform: openNewAbsence,
      keywords: ['лікарняний', 'відсутній'],
    },
    {
      id: 'new-change',
      label: 'Нова зміна: додаткова пара, аудиторія, викладач',
      group: 'Заміни',
      icon: FilePlus2,
      shortcut: 'c',
      perform: openNewChange,
      keywords: ['додаткова пара', 'відпрацювання', 'аудиторія', 'вичитано', 'заміна без відсутності'],
    },
    {
      id: 'order-draft',
      label: 'Відкрити чернетку розпорядження',
      group: 'Заміни',
      icon: FileText,
      perform: () => void navigate(`/admin/orders?date=${date}`),
    },
  ]);

  // відповідь викладача в Telegram: тост одним реченням і короткий «пульс» на рядку пари
  useServerEvent('change.updated', (change) => {
    const last = change.history.at(-1);
    if (!last || !teacherAnswered(last.from, last.to, change.approvalMode)) return;
    if (actions.isOwn(change.id) || change.date < workspace.from || change.date > workspace.to) return;
    const entry = workspace.entries.find((e) => e.item.decision?.changeId === change.id);
    const toast = answerToast(change, change.new.teacher ? name(change.new.teacher) : 'Викладач');
    notify[toast.tone](toast.text);
    if (entry) pulse(entry.item.lesson.id);
  });

  /**
   * Відсутність — у журнал дій. Додану скасовує видалення (разом з її рішеннями, ще не внесеними в
   * розпорядження); змінену — попередні дати й пари з версією після правки. Рішення, які правка вже
   * скасувала, не повертаються — про це каже примітка.
   */
  const journalAbsence = (before: AbsenceDto | null, saved: AbsenceDto) => {
    const where = `${name(saved.teacher)}, ${absencePeriod(saved)}`;
    const invalidate = [qk.admin.all];
    if (!before) {
      track({
        label: `Додано відсутність — ${where}`,
        note: 'Скасування прибере й рішення за нею, ще не внесені в розпорядження.',
        invalidate,
        undo: () => cancelAbsence.mutateAsync(saved.id),
      });
      return;
    }
    let version = saved.version;
    const put = async (from: AbsenceDto) => {
      const result = await updateAbsence.mutateAsync({ id: saved.id, body: { ...absenceRequest(from), version } });
      version = result.version;
    };
    track({
      label: `Змінено відсутність — ${where}`,
      note: 'Рішення, які ця правка скасувала, після скасування не повернуться.',
      invalidate,
      undo: () => put(before),
      redo: () => put(saved),
    });
  };

  const cancelAbsenceFlow = async (absence: AbsenceDto) => {
    const ok = await confirm({
      title: 'Викладач на місці — скасувати відсутність?',
      description: 'Рішення за цією відсутністю, ще не внесені в розпорядження, теж скасуються.',
      objectName: `${name(absence.teacher)}, ${absencePeriod(absence)}`,
      confirmLabel: 'Скасувати відсутність',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await cancelAbsence.mutateAsync(absence.id);
      if (selectedAbsenceId === absence.id) updateParams({ absence: null, lesson: null });
      notify.success(`Відсутність скасовано: ${name(absence.teacher)}`);
      trackFinal(
        `Скасовано відсутність — ${name(absence.teacher)}, ${absencePeriod(absence)}`,
        'Скасовану відсутність не повернути: додайте її знову',
      );
    } catch (error) {
      notify.error(error, 'Не вдалося скасувати відсутність');
    }
  };

  const others = standaloneChanges(workspace.changesById.values());
  const cancelStandalone = async (change: ChangeDto) => {
    const declined = change.status === 'DECLINED';
    const ok = await confirm({
      title: declined ? 'Прибрати зміну з відмовою?' : 'Скасувати зміну?',
      description: declined
        ? 'Викладач уже відмовився, тож у розкладі групи цієї зміни немає. Її просто приберемо зі списку — нікому нічого не надішлемо.'
        : change.status === 'PENDING_CONFIRMATION' || change.status === 'CONFIRMED'
          ? 'Розклад групи повернеться як був. Якщо викладачу вже надіслали запит, бот повідомить про скасування.'
          : 'Розклад групи повернеться як був.',
      objectName: `${standaloneHeading(change, true)} — ${decisionSummary(change, name)}`,
      confirmLabel: declined ? 'Прибрати' : 'Скасувати зміну',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await cancelChange.mutateAsync(change.id);
      notify.success(`Зміну скасовано: ${standaloneHeading(change, true)}`);
      trackFinal(`Скасовано зміну — ${standaloneHeading(change, true)}`, 'Скасовану зміну не повернути: додайте її знову');
    } catch (error) {
      notify.error(error, 'Не вдалося скасувати зміну');
    }
  };
  const othersList = (
    <StandaloneChanges
      changes={others}
      showDates={period === 'week'}
      onCancel={(change) => void cancelStandalone(change)}
      onReplace={openChangeWith}
    />
  );

  const tally = tallyEntries(workspace.entries);
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

  // перше завантаження дати чи періоду: панель чекає на всі запити (відсутні, їхні пари, рішення) і доти
  // показує силует тієї ж форми — без «напівзаповненої» панелі, що потім стрибає. Живі оновлення вже не ховають її.
  const viewKey = `${date}|${period}`;
  const [settledKey, setSettledKey] = useState<string | null>(null);
  if (!workspace.loading && settledKey !== viewKey) setSettledKey(viewKey);
  const settling = workspace.loading && settledKey !== viewKey;

  const showDates = period === 'week' || workspace.absences.some((a) => a.dateFrom !== a.dateTo);
  // пари обраного відсутнього ще в дорозі (чи не прийшли) — не порожня колонка, а силует чи пояснення
  const selectedLessons = selectedAbsenceId ? workspace.lessonsStatus.get(selectedAbsenceId) : undefined;
  const lessonsPlaceholder = (before?: ReactNode) =>
    selectedAbsence || workspace.loading ? (
      <div className="flex flex-col gap-3">
        {before}
        {!selectedAbsence || selectedLessons === 'pending' ? (
          <LoadingRegion label="Завантажуємо пари…" className="flex flex-col gap-2 px-2">
            <Skeleton className="h-5 w-1/2" />
            {[0, 1, 2].map((key) => (
              <Skeleton key={key} className="h-16 w-full rounded-popover" />
            ))}
          </LoadingRegion>
        ) : (
          <p className="px-3 py-2 text-body text-secondary">
            {selectedLessons === 'failed'
              ? `Пари ${name(selectedAbsence.teacher)} не вдалося завантажити.`
              : `У ${name(selectedAbsence.teacher)} пар у цей період немає.`}
          </p>
        )}
      </div>
    ) : null;
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
    ) : (
      lessonsPlaceholder(before)
    );

  const absenceList = (drill: boolean) => (
    <AbsenceList
      absences={workspace.absences}
      entries={workspace.entries}
      lessonsStatus={workspace.lessonsStatus}
      selectedId={selectedAbsenceId}
      onSelect={(id) => selectAbsence(id)}
      onAdd={openNewAbsence}
      onAddChange={narrow ? openNewChange : undefined}
      loading={workspace.loading}
      drill={drill}
      showDates={period === 'week'}
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
  } else if (settling) {
    body = (
      <LoadingRegion label="Завантажуємо відсутніх і їхні пари…">
        <WorkspaceSkeleton panes={panes} />
      </LoadingRegion>
    );
  } else if (workspace.absences.length === 0) {
    body = (
      <div className="flex flex-col gap-4">
        <EmptyState
          title={period === 'day' ? `На ${formatWeekdayDateAfterNa(date)} ніхто не відсутній.` : 'Цього тижня ніхто не відсутній.'}
          description="Коли викладач повідомить про відсутність через бота чи кабінет, його пари зʼявляться тут разом з варіантами заміни. Додаткову пару, іншу аудиторію чи ручну заміну без відсутності додає «Нова зміна»."
          action={
            <Tooltip content={null} shortcut="n">
              <Button variant="ghost" className="-ms-3" onClick={openNewAbsence} iconStart={<Plus aria-hidden="true" className="size-4" />}>
                Додати відсутність
              </Button>
            </Tooltip>
          }
          secondaryAction={
            <Button variant="ghost" onClick={openNewChange} iconStart={<FilePlus2 aria-hidden="true" className="size-4" />}>
              Нова зміна
            </Button>
          }
        />
        {others.length > 0 ? <div className="rounded-panel border border-default bg-surface p-3">{othersList}</div> : null}
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
          {othersList}
        </section>
        <div className="min-w-0 border-s border-separator p-3 pt-5">{lessons()}</div>
        <aside aria-label="Рішення" className="min-w-0 border-s border-separator">
          <div className={STICKY}>
            <div className={cn('p-5 lg:py-2.5 lg:ps-5 lg:pe-3.5', STICKY_SCROLL)}>{optionsPanel(true)}</div>
          </div>
        </aside>
      </div>
    );
  } else if (panes === 2) {
    body = (
      <div className="flex flex-col gap-4">
        <AbsenceStrip
          absences={workspace.absences}
          entries={workspace.entries}
          lessonsStatus={workspace.lessonsStatus}
          selectedId={selectedAbsenceId}
          onSelect={(id) => selectAbsence(id)}
          onAdd={openNewAbsence}
        />
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] items-stretch rounded-panel border border-default bg-surface">
          <div className="min-w-0 p-3 pt-5">{lessons()}</div>
          <aside aria-label="Рішення" className="min-w-0 border-s border-separator">
            <div className={STICKY}>
              <div className={cn('p-5 lg:py-2.5 lg:ps-5 lg:pe-3.5', STICKY_SCROLL)}>{optionsPanel(true)}</div>
            </div>
          </aside>
        </div>
        {others.length > 0 ? <div className="rounded-panel border border-default bg-surface p-3">{othersList}</div> : null}
      </div>
    );
  } else {
    // телефон і вузький планшет: по рівнях, і з кожного видно дорогу назад
    body = (
      <div className="rounded-panel border border-default bg-surface p-2 pt-3">
        {selectedAbsence ? (
          lessons(
            <button
              type="button"
              onClick={() => selectAbsence(null)}
              className="ms-1 inline-flex min-h-9 w-fit items-center gap-0.5 rounded-control pe-2 text-ui font-[550] text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
            >
              <ChevronLeft aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
              Усі відсутні
            </button>,
          )
        ) : (
          <>
            {absenceList(true)}
            {othersList}
          </>
        )}
      </div>
    );
  }

  // відсутні вже є, а пари чи рішення не прийшли — одна смуга з повтором над робочою панеллю
  const partialError =
    workspace.error && workspace.absences.length > 0 && !weekend ? (
      <PageErrorBanner error={workspace.error} title="Не вдалося завантажити пари відсутніх" onRetry={workspace.retry} retrying={workspace.loading} />
    ) : null;

  return (
    <div ref={rootRef} className="flex flex-col gap-6">
      <PageHeader
        title="Заміни"
        meta={
          weekend || (!settling && workspace.absences.length === 0) ? null : settling ? (
            // поки пари й рішення їдуть, лічильник збрехав би («1 з 3» замість «4 з 8») — тримаємо місце
            <span aria-hidden="true" className="flex h-[1.375rem] items-center">
              <Skeleton className="h-3 w-48" />
            </span>
          ) : workspace.entries.length > 0 ? (
            <ResolvedProgress done={tally.done} waiting={tally.waiting} total={tally.total} />
          ) : null
        }
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
            <Tooltip content="Додаткова пара, інша аудиторія чи заміна без відсутності" shortcut="c">
              <Button
                variant="secondary"
                onClick={openNewChange}
                iconStart={<FilePlus2 aria-hidden="true" className="size-4" />}
                className="max-sm:hidden"
              >
                Нова зміна
              </Button>
            </Tooltip>
            <Tooltip content="Відкрити розпорядження на цей день">
              <Link
                to={`/admin/orders?date=${date}`}
                className={buttonClass({
                  variant: 'secondary',
                  className: 'max-sm:hidden',
                })}
              >
                <FileText aria-hidden="true" className="size-4" />
                Розпорядження
              </Link>
            </Tooltip>
          </>
        }
        // `⋯` тримає лише те, чого на екрані немає: «Додати відсутність» видно в списку відсутніх, тож у меню
        // вона потрібна тільки на телефоні на рівні пар; кнопка «Розпорядження» ховається на вузькому
        more={[
          ...(panes === 1 && selectedAbsence && !weekend
            ? [
                {
                  label: 'Нова відсутність',
                  icon: <CalendarPlus aria-hidden="true" />,
                  onSelect: openNewAbsence,
                },
              ]
            : []),
          ...(narrow
            ? [
                {
                  label: 'Нова зміна',
                  icon: <FilePlus2 aria-hidden="true" />,
                  onSelect: openNewChange,
                },
                {
                  label: 'Розпорядження',
                  icon: <FileText aria-hidden="true" />,
                  to: `/admin/orders?date=${date}`,
                },
              ]
            : []),
        ]}
      />

      {partialError}
      {body}

      {panes === 1 ? (
        <Drawer direction="right" open={Boolean(explicit)} onOpenChange={(open) => !open && updateParams({ lesson: null })}>
          <DrawerContent side="right" title={explicit ? `${explicit.item.lesson.group.code}, ${explicit.item.lesson.pair} пара` : 'Варіанти'}>
            {optionsPanel(false)}
          </DrawerContent>
        </Drawer>
      ) : null}

      <NewChangeDialog
        key={urlPrefill ? `url:${params.get('group')}:${params.get('discipline')}:${params.get('suggest')}` : `seq:${changeDialog.seq}`}
        open={changeDialog.open || urlPrefill !== null}
        onOpenChange={(open) => (open ? setChangeDialog((prev) => ({ ...prev, open })) : closeNewChange())}
        date={date}
        semester={meta.data?.semester ?? null}
        prefill={urlPrefill ?? changeDialog.prefill}
        approvalMode={approvalMode}
      />

      <AbsenceDialog
        key={dialog.seq}
        open={dialog.open}
        onOpenChange={(open) => setDialog((prev) => ({ ...prev, open }))}
        date={date}
        absence={dialog.absence}
        semester={meta.data?.semester ?? null}
        onSaved={(absence, created, before) => {
          updateParams({ absence: absence.id, lesson: null });
          journalAbsence(created ? null : before, absence);
        }}
      />
    </div>
  );
}
