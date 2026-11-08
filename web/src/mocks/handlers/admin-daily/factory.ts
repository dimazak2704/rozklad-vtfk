/**
 * Побудова записів зміни: однаково для демо-сіду й для POST /api/admin/changes.
 * Статус після створення — за правилами погодження (DOMAIN §7): новий викладач у режимі CONFIRM —
 * чекаємо підтвердження, інакше одразу підтверджено.
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
} from '@/api/types';
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

/** Чи треба питати викладача: є новий викладач і режим «з підтвердженням». */
export function needsConfirmation(kind: ChangeKind, newTeacher: TeacherRef | null | undefined, mode: ApprovalMode): boolean {
  return mode === 'CONFIRM' && Boolean(newTeacher) && kind !== 'CANCEL';
}

export function historyEntry(at: Date, actor: string, from: ChangeStatus | null, to: ChangeStatus, comment: string | null = null): ChangeHistoryEntry {
  return { at: at.toISOString(), actor, from, to, comment };
}

export function createdComment(status: ChangeStatus, kind: ChangeKind, mode: ApprovalMode = 'AUTO'): string | null {
  if (status === 'PENDING_CONFIRMATION') return 'Запит на підтвердження надіслано в Telegram';
  if (kind === 'CANCEL') return null;
  if (mode === 'NOTIFY') return 'Створено і підтверджено, викладачу надіслано повідомлення';
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
  const status: ChangeStatus = needsConfirmation(input.kind, newTeacher, options.mode) ? 'PENDING_CONFIRMATION' : 'CONFIRMED';
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
    confirmDeadline:
      status === 'PENDING_CONFIRMATION' ? new Date(options.at.getTime() + options.timeoutMinutes * 60_000).toISOString() : null,
    respondedAt: null,
    orderId: null,
    history: [
      historyEntry(options.at, options.actor, null, status, createdComment(status, input.kind, changeApprovalMode(input.kind, newTeacher, options.mode))),
    ],
    approvalMode: options.mode === 'NOTIFY' ? changeApprovalMode(input.kind, newTeacher, options.mode) : options.mode,
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
