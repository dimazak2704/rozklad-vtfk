/**
 * Побудова записів зміни: однаково для демо-сіду й для POST /api/admin/changes.
 * Статус після створення — за правилами погодження (DOMAIN §7): новий викладач у режимі CONFIRM —
 * чекаємо підтвердження, інакше одразу підтверджено. Як у core (ConfirmationWindow), про пару, що вже
 * минула, викладача не питають, а строк відповіді не пізніший за початок пари.
 */
import type {
  ApprovalMode,
  ChangeHistoryEntry,
  ChangeKind,
  ChangeStatus,
  DisciplineRef,
  IsoDate,
  LessonView,
  ReasonCode,
  RoomRef,
  TeacherRef,
  Uuid,
  Weekday,
} from '@/api/types';
import { compareIsoDates, diffDays, isoWeekday, kyivClock, timeToMinutes } from '@/lib/date';
import { bellSlots } from '../../fixtures';
import { printedReason } from './printed';
import type { ChangeRecord } from './world';

export interface ChangeInput {
  id: Uuid;
  kind: ChangeKind;
  date: IsoDate;
  group: LessonView['group'];
  subgroup: number;
  pairFrom: number;
  pairTo: number | null;
  /** Заняття, яке закриває зміна (немає для додаткової пари). */
  lesson: LessonView | null;
  swapLesson?: LessonView | null;
  newTeacher?: TeacherRef | null;
  newDiscipline?: DisciplineRef | null;
  newRoom?: RoomRef | null;
  reasonCode: ReasonCode;
  reasonText?: string | null;
  absenceId?: Uuid | null;
}

// ───────────────────────── Вікно підтвердження (як ConfirmationWindow у core) ─────────────────────────

type PairSpan = Pick<ChangeRecord, 'date' | 'pairFrom' | 'pairTo'>;

/** Момент «`date`, `minutes` від півночі за Києвом», відлічений від `now` (пари не припадають на перехід часу). */
function kyivMoment(date: IsoDate, minutes: number, now: Date): number {
  const clock = kyivClock(now);
  const shift = diffDays(clock.date, date) * 1_440 + minutes - clock.minutes;
  return now.getTime() + (shift * 60 - clock.seconds) * 1_000 - now.getUTCMilliseconds();
}

/** Початок першої й кінець останньої пари зміни за дзвінками дня; null — дзвінка на пару немає. */
function pairMoments(change: PairSpan, now: Date): { start: number | null; end: number | null } {
  const slots = bellSlots(isoWeekday(change.date) as Weekday);
  const first = slots.find((slot) => slot.pair === change.pairFrom);
  const last = slots.find((slot) => slot.pair === change.pairTo);
  return {
    start: first ? kyivMoment(change.date, timeToMinutes(first.startsAt), now) : null,
    end: last ? kyivMoment(change.date, timeToMinutes(last.endsAt), now) : null,
  };
}

/** Пари зміни вже минули: давніший день або сьогодні після кінця останньої пари (core: `lessonOver`). */
export function lessonOver(change: PairSpan, now: Date): boolean {
  const today = kyivClock(now).date;
  if (change.date !== today) return compareIsoDates(change.date, today) < 0;
  const { end } = pairMoments(change, now);
  return end !== null && end <= now.getTime();
}

/**
 * Строк відповіді раунду, що починається в `now` (core: `deadline`): `now + timeout`, але не пізніше за початок
 * першої пари; коли пара вже йде, відповідь ще корисна до її кінця — тоді строк обмежує кінець.
 */
export function confirmDeadline(change: PairSpan, now: Date, timeoutMinutes: number): string {
  const byTimeout = now.getTime() + timeoutMinutes * 60_000;
  const { start, end } = pairMoments(change, now);
  if (start !== null && start > now.getTime()) return new Date(Math.min(start, byTimeout)).toISOString();
  if (end !== null && end > now.getTime() && end < byTimeout) return new Date(end).toISOString();
  return new Date(byTimeout).toISOString();
}

/** Чи треба питати викладача: є новий викладач і режим «з підтвердженням». */
export function needsConfirmation(kind: ChangeKind, newTeacher: TeacherRef | null | undefined, mode: ApprovalMode): boolean {
  return mode === 'CONFIRM' && Boolean(newTeacher) && kind !== 'CANCEL';
}

export function historyEntry(at: Date, actor: string, from: ChangeStatus | null, to: ChangeStatus, comment: string | null = null): ChangeHistoryEntry {
  return { at: at.toISOString(), actor, from, to, comment };
}

export function createdComment(status: ChangeStatus, kind: ChangeKind, mode: ApprovalMode = 'AUTO', pairOver = false): string | null {
  if (status === 'PENDING_CONFIRMATION') return 'Запит на підтвердження надіслано в Telegram';
  if (kind === 'CANCEL') return null;
  if (mode === 'NOTIFY') return 'Створено і підтверджено, викладачу надіслано повідомлення';
  if (pairOver) return 'Створено і підтверджено: пара вже минула, викладача не питали';
  return 'Підтверджено без запиту: режим «Автоматично»';
}

/** Як ChangeWorkflow.open у core: без нового викладача — AUTO, інакше режим налаштувань. */
export function changeApprovalMode(kind: ChangeKind, newTeacher: TeacherRef | null | undefined, mode: ApprovalMode): ApprovalMode {
  return newTeacher && kind !== 'CANCEL' ? mode : 'AUTO';
}

export function makeChange(
  input: ChangeInput,
  options: { at: Date; actor: string; mode: ApprovalMode; timeoutMinutes: number },
): ChangeRecord {
  // перестановка й обʼєднання беруть викладача й дисципліну з іншого заняття (переставленого чи іншої підгрупи)
  const swap = input.kind === 'SWAP' || input.kind === 'MERGE_SUBGROUPS' ? (input.swapLesson ?? null) : null;
  const merge = input.kind === 'MERGE_SUBGROUPS';
  const newTeacher = swap ? (swap.teacher ?? null) : (input.newTeacher ?? null);
  const span = { date: input.date, pairFrom: input.pairFrom, pairTo: input.pairTo ?? input.pairFrom };
  // як у core: просити підтвердити пару, що вже минула, запізно — зміна підтверджується одразу, як в AUTO
  const over = needsConfirmation(input.kind, newTeacher, options.mode) && lessonOver(span, options.at);
  const mode: ApprovalMode = over ? 'AUTO' : options.mode;
  const status: ChangeStatus = needsConfirmation(input.kind, newTeacher, mode) ? 'PENDING_CONFIRMATION' : 'CONFIRMED';
  const lesson = input.lesson;
  const reasonText = input.reasonText?.trim() || null;
  return {
    id: input.id,
    kind: input.kind,
    status,
    date: input.date,
    // як у core: діапазон пар; для однієї пари (і для SWAP) pairTo = pairFrom, звідки переставили — swapFromPair
    pairFrom: input.pairFrom,
    pairTo: input.pairTo ?? input.pairFrom,
    group: input.group,
    subgroup: input.subgroup,
    original: {
      discipline: lesson?.discipline ?? null,
      teacher: lesson?.teacher ?? null,
      room: lesson?.room ?? null,
    },
    new:
      input.kind === 'CANCEL'
        ? { discipline: null, teacher: null, room: null }
        : {
            discipline: swap ? swap.discipline : (input.newDiscipline ?? lesson?.discipline ?? null),
            teacher: newTeacher,
            // переставлена пара йде в аудиторію звільненої пари; обʼєднана — до викладача іншої підгрупи
            room: input.newRoom ?? (merge ? (swap?.room ?? null) : (lesson?.room ?? null)),
          },
    reasonCode: input.reasonCode,
    reasonText,
    absenceId: input.absenceId ?? null,
    confirmDeadline: status === 'PENDING_CONFIRMATION' ? confirmDeadline(span, options.at, options.timeoutMinutes) : null,
    respondedAt: null,
    orderId: null,
    history: [
      historyEntry(options.at, options.actor, null, status, createdComment(status, input.kind, changeApprovalMode(input.kind, newTeacher, mode), over)),
    ],
    approvalMode: mode === 'NOTIFY' ? changeApprovalMode(input.kind, newTeacher, mode) : mode,
    originalLessonId: lesson?.id ?? null,
    swapLessonId: swap?.id ?? null,
    swapFromPair: merge ? null : (swap?.pair ?? null),
    printedReason: printedReason(input.kind, input.reasonCode, reasonText),
    responseComment: null,
    remindedAt: null,
    createdBy: options.actor,
    createdAt: options.at.toISOString(),
    version: 0,
    supersededBy: null,
    teacherAnswer: null,
  };
}

/** Новий стан зі записом в історію (DOMAIN §7: кожна зміна стану — у change_history). */
export function transition(
  change: ChangeRecord,
  to: ChangeStatus,
  at: Date,
  actor: string,
  comment: string | null = null,
  patch: Partial<ChangeRecord> = {},
): ChangeRecord {
  return {
    ...change,
    ...patch,
    status: to,
    history: [...change.history, historyEntry(at, actor, change.status, to, comment)],
    // кожне збереження в core збільшує версію (оптимістичне блокування)
    version: change.version + 1,
  };
}
