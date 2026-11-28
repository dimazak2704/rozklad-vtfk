import type { AbsenceReasonCategory, AbsenceSource, AbsenceStatus, ApprovalMode, ChangeKind, ChangeStatus, DisciplineRef, GenerationJobStatus, GroupRef, IsoDate, IsoInstant, Nullable, ReasonCode, ReplyDecision, RoomRef, TeacherRef, Uuid, WeekParity } from './common';
import type { LessonView } from './public';

// ───────────────────────── 2. Admin: заміни ─────────────────────────

/** GET /api/admin/absences (масив активних, що перетинають `[from, to]`), GET /api/me/absences. */
export interface AbsenceDto {
  id: Uuid;
  teacher: TeacherRef;
  dateFrom: IsoDate;
  dateTo: IsoDate;
  /** null — увесь день */
  pairs: Nullable<number[]>;
  reasonCategory: AbsenceReasonCategory;
  comment: Nullable<string>;
  source: AbsenceSource;
  status: AbsenceStatus;
  createdBy: Nullable<string>;
  createdAt: IsoInstant;
  /** Для оптимістичного блокування: PUT надсилає її назад (застаріла → 409 `stale-data`). */
  version: number;
}

/**
 * Подія SSE `absence.created` / `absence.updated`: без причини й коментаря (DOMAIN §7 — вони приватні).
 */
export interface AbsenceSummaryDto {
  id: Uuid;
  teacher: TeacherRef;
  dateFrom: IsoDate;
  dateTo: IsoDate;
  pairs: Nullable<number[]>;
  source: AbsenceSource;
  status: AbsenceStatus;
}

/** POST/PUT /api/admin/absences. Викладача відсутності змінити не можна (422 `ABSENCE_TEACHER_FIXED`). */
export interface AbsenceRequest {
  teacherId: Uuid;
  dateFrom: IsoDate;
  dateTo: IsoDate;
  pairs?: Nullable<number[]>;
  reasonCategory: AbsenceReasonCategory;
  /** До 1000 символів. */
  comment?: Nullable<string>;
  /** Лише в PUT: версія, яку бачив диспетчер. */
  version?: Nullable<number>;
}

/** GET /api/admin/absences/{id}/affected */
export interface AffectedLessonDto {
  /** Дата пари: відсутність може тривати кілька днів. */
  date: IsoDate;
  lesson: LessonView;
  /** null або DECLINED — пара ще потребує рішення. */
  decision: Nullable<{ changeId: Uuid; status: ChangeStatus; kind: ChangeKind }>;
  /**
   * true — відсутній викладач не веде цю пару, а мав замінити на ній іншого (`decision` — ця зміна):
   * її вже не можна підтвердити чи опублікувати, треба скасувати й обрати інше рішення.
   */
  asSubstitute: boolean;
}

/** Смуга варіанта (DOMAIN §7): порівнюється першою, далі вид і внутрішня вартість (її сервер не віддає). */
export type OptionBand = 'CLEAN' | 'EASY_CANCEL' | 'CAUTION' | 'LAST';

/**
 * Варіант рішення в порядку сервера (Round 7, рекомендатор v2). Види: REPLACE_DISCIPLINE, MERGE_SUBGROUPS,
 * SWAP, CANCEL — будь-яка підмножина; REPLACE_TEACHER і ADDITIONAL `/options` більше не дає.
 */
export interface OptionDto {
  kind: ChangeKind;
  /** @deprecated Лише позиція (100, 90, …). Інтерфейс її не показує. */
  score: number;
  title: string;
  /** Інформаційні чипи, найважливіший перший. */
  reasons: string[];
  teacher: Nullable<TeacherRef>;
  discipline: Nullable<DisciplineRef>;
  room: Nullable<RoomRef>;
  /** SWAP — переставлене заняття; MERGE_SUBGROUPS — заняття іншої підгрупи на цій парі. */
  swapLesson: Nullable<LessonView>;
  /** Чипи «⚠». */
  warnings: string[];
  /** Завжди null (відпрацювання — ручна дія «Додати пару»). */
  date: Nullable<IsoDate>;
  pair: Nullable<number>;
  band: OptionBand;
  /** Лише в першого варіанта і лише коли він явно кращий за другий (ніколи для минулої пари). */
  recommended: boolean;
  /** Аудиторія, яку варіант пропонує (своя аудиторія замінника чи аудиторія іншої підгрупи). */
  suggestedRoom: Nullable<RoomRef>;
}

/** Хто вже відмовився від запиту на цю пару і коли. */
export interface DeclinedDto {
  teacher: TeacherRef;
  at: IsoInstant;
}

/** GET /api/admin/lessons/{lessonId}/options */
export interface LessonOptionsDto {
  lesson: LessonView;
  options: OptionDto[];
  declined: DeclinedDto[];
  /** Пара вже минула: диспетчер позначає, що було насправді, нічого не рекомендовано. */
  past: boolean;
}

export type TeacherAvailabilityStatus = 'FREE' | 'BUSY' | 'ABSENT' | 'UNAVAILABLE' | 'DECLINED';

/** Елемент GET /api/admin/lessons/{lessonId}/teacher-availability?q= (ручне «Інший викладач…»). */
export interface TeacherAvailabilityDto {
  teacher: TeacherRef;
  status: TeacherAvailabilityStatus;
  /** BUSY — «3 пара, 4КІ2»; DECLINED — «14:05» (Київ); інакше null. */
  detail: Nullable<string>;
}

/** Пара плану дня з її варіантами й запропонованим (`proposedIndex` у `options`). */
export interface PlannedLessonDto {
  lesson: LessonView;
  options: OptionDto[];
  /** null — пара минула або нічого не підходить. */
  proposedIndex: Nullable<number>;
  declined: DeclinedDto[];
  past: boolean;
}

/** GET /api/admin/absences/{id}/plan?date= — пропозиція, нічого не створено. */
export interface DayPlanDto {
  date: IsoDate;
  lessons: PlannedLessonDto[];
}

/** POST /api/admin/changes/batch → 201 ChangeDto[] у порядку запиту; одна транзакція. */
export interface BatchChangesRequest {
  changes: CreateChangeRequest[];
}

/** POST /api/admin/changes → 201 ChangeDto / 409 conflicts */
export interface CreateChangeRequest {
  kind: ChangeKind;
  date: IsoDate;
  pairFrom: number;
  pairTo?: Nullable<number>;
  groupId: Uuid;
  subgroup?: Nullable<number>;
  originalLessonId?: Nullable<Uuid>;
  newDisciplineId?: Nullable<Uuid>;
  newTeacherId?: Nullable<Uuid>;
  newRoomId?: Nullable<Uuid>;
  swapLessonId?: Nullable<Uuid>;
  reasonCode: ReasonCode;
  reasonText?: Nullable<string>;
  absenceId?: Nullable<Uuid>;
}

export interface ChangeHistoryEntry {
  at: IsoInstant;
  actor: string;
  from: Nullable<ChangeStatus>;
  to: ChangeStatus;
  comment?: Nullable<string>;
}

export interface ChangeParties {
  discipline: Nullable<DisciplineRef>;
  teacher: Nullable<TeacherRef>;
  room: Nullable<RoomRef>;
}

/**
 * ChangeDto з core. Пари — діапазон `[pairFrom, pairTo]` (`pairTo` = `pairFrom` для однієї пари; ширший лише
 * в BLOCK/ADDITIONAL). Для SWAP `pairFrom` — звільнений слот, а пара, з якої переставили заняття, — `swapFromPair`.
 * Адитивні поля core: `approvalMode` … `version` (CHANGELOG-CONTRACTS, розділ Substitutions).
 */
export interface ChangeDto {
  id: Uuid;
  kind: ChangeKind;
  status: ChangeStatus;
  date: IsoDate;
  pairFrom: number;
  pairTo: number;
  group: GroupRef;
  subgroup: number;
  original: ChangeParties;
  new: ChangeParties;
  reasonCode: ReasonCode;
  reasonText: Nullable<string>;
  absenceId: Nullable<Uuid>;
  confirmDeadline: Nullable<IsoInstant>;
  respondedAt: Nullable<IsoInstant>;
  orderId: Nullable<Uuid>;
  history: ChangeHistoryEntry[];
  approvalMode: ApprovalMode;
  originalLessonId: Nullable<Uuid>;
  swapLessonId: Nullable<Uuid>;
  /** SWAP: пара, з якої переставили заняття (інакше null). */
  swapFromPair: Nullable<number>;
  /** Що друкує розпорядження в колонці «Причина» («Відп.», «Зв.зам.»); null — без причини. */
  printedReason: Nullable<string>;
  /** Коментар викладача до відповіді. */
  responseComment: Nullable<string>;
  remindedAt: Nullable<IsoInstant>;
  createdBy: Nullable<string>;
  createdAt: IsoInstant;
  /** Версія для оптимістичного блокування: PUT надсилає її назад (застаріла → 409 `stale-data`). */
  version: number;
  /**
   * Адитивно (core, R4): опублікована пізніше зміна, що вирішила долю пари, яку поставила ця зміна
   * (доповнення скасувало пару замінника чи віддало її іншому); `null`, якщо такої немає.
   */
  supersededBy?: Nullable<ChangeSuccessorDto>;
  /**
   * Адитивно (core, R7): відповідь призначеного викладача в поточному раунді — `CONFIRM` («Підтверджую» або
   * «Добре»), `DECLINE` («Не можу»), `null` — ще не відповів. NOTIFY + APPLIED + DECLINE — заперечення після
   * публікації: потрібне доповнення до розпорядження.
   */
  teacherAnswer?: Nullable<ReplyDecision>;
}

/** Зміна, що пізніше скасувала чи перепризначила пару застосованої зміни. */
export interface ChangeSuccessorDto {
  changeId: Uuid;
  kind: ChangeKind;
  orderId: Nullable<Uuid>;
}

/**
 * PUT /api/admin/changes/{id}: лише аудиторія й причина (інший викладач чи дисципліна = DELETE + POST).
 * `newRoomId` замінює аудиторію рішення, тож щоб її зберегти, надсилаємо поточну.
 */
export interface UpdateChangeRequest {
  newRoomId: Nullable<Uuid>;
  reasonCode: ReasonCode;
  /** До 100 символів. */
  reasonText?: Nullable<string>;
  version: number;
}

/** Види сповіщень панелі, які надсилає core (CHANGELOG-CONTRACTS, Dashboard). */
export type DashboardAlertKind = 'NO_SEMESTER' | 'DECLINED' | 'UNCOVERED_LESSONS' | 'PENDING_OVERDUE' | 'ORDER_NOT_PUBLISHED';

export interface DashboardAlert {
  /** Стабільний код; невідомий новий код показуємо за `severity`. */
  kind: DashboardAlertKind | (string & {});
  severity: 'INFO' | 'WARN' | 'ERROR';
  message: string;
  /** Маршрут SPA з датою, напр. `/admin/changes?date=2026-10-19`; null — без переходу. */
  link: Nullable<string>;
}

/** GET /api/admin/dashboard?date */
export interface DashboardDto {
  date: IsoDate;
  /** null — дата поза семестром. */
  parity: Nullable<WeekParity>;
  absences: number;
  uncoveredLessons: number;
  /** Частина `uncoveredLessons`, про яку ще ніхто нічого не вирішив: не перетинається з `pending` і `declined`. */
  undecidedLessons: number;
  pending: number;
  confirmed: number;
  declined: number;
  orderDraft: Nullable<{ id: Uuid; itemCount: number }>;
  generation: Nullable<{ status: GenerationJobStatus }>;
  /** Завжди null: витрати ШІ рахує importer (`GET /api/import/ai/usage`). */
  aiSpendToday: Nullable<number>;
  alerts: DashboardAlert[];
}
