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

export function createdComment(status: ChangeStatus, kind: ChangeKind): string | null {
  if (status === 'PENDING_CONFIRMATION') return 'Запит на підтвердження надіслано в Telegram';
  if (kind === 'CANCEL') return null;
  return 'Підтверджено без запиту: режим «Автоматично»';
}

export function makeChange(
  input: ChangeInput,
  options: { at: Date; actor: string; mode: ApprovalMode; timeoutMinutes: number },
): ChangeRecord {
  const swap = input.kind === 'SWAP' ? (input.swapLesson ?? null) : null;
  const newTeacher = swap ? (swap.teacher ?? null) : (input.newTeacher ?? null);
  const status: ChangeStatus = needsConfirmation(input.kind, newTeacher, options.mode) ? 'PENDING_CONFIRMATION' : 'CONFIRMED';
  const lesson = input.lesson;
  return {
    id: input.id,
    kind: input.kind,
    status,
    date: input.date,
    pairFrom: input.pairFrom,
    pairTo: swap ? swap.pair : input.pairTo,
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
            // переставлена пара йде в аудиторію звільненої пари: та вже вільна
            room: input.newRoom ?? lesson?.room ?? null,
          },
    reasonCode: input.reasonCode,
    reasonText: input.reasonText?.trim() || null,
    absenceId: input.absenceId ?? null,
    confirmDeadline:
      status === 'PENDING_CONFIRMATION' ? new Date(options.at.getTime() + options.timeoutMinutes * 60_000).toISOString() : null,
    respondedAt: null,
    orderId: null,
    history: [historyEntry(options.at, options.actor, null, status, createdComment(status, input.kind))],
    createdAt: options.at.toISOString(),
    originalLessonId: lesson?.id ?? null,
    swapLessonId: swap?.id ?? null,
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
  };
}
