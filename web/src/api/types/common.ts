// ───────────────────────── Примітиви ─────────────────────────

export type Uuid = string;
/** `2026-10-19` */
export type IsoDate = string;
/** `08:30` */
export type IsoTime = string;
/** ISO-8601 UTC instant: `2026-10-19T05:30:00Z` */
export type IsoInstant = string;
/** Понеділок = 1 … пʼятниця = 5 (навчальні дні). */
export type Weekday = 1 | 2 | 3 | 4 | 5;
export type Nullable<T> = T | null;

// ───────────────────────── Спільні enum ─────────────────────────

export type Parity = 'BOTH' | 'NUMERATOR' | 'DENOMINATOR';
export type WeekParity = Exclude<Parity, 'BOTH'>;
export type LessonKind = 'LECTURE' | 'PRACTICE' | 'LAB' | 'SEMINAR' | 'MIXED';
export type LessonViewKind =
  | 'REGULAR'
  | 'SUBSTITUTED'
  | 'CANCELLED'
  | 'ADDITIONAL'
  | 'MOVED'
  | 'ROOM_CHANGED'
  | 'BLOCK';
export type ChangeKind =
  | 'REPLACE_TEACHER'
  | 'REPLACE_DISCIPLINE'
  | 'SWAP'
  | 'CANCEL'
  | 'ADDITIONAL'
  | 'ROOM_CHANGE'
  | 'BLOCK'
  | 'DISCIPLINE_FINISHED'
  /** Round 7: викладач іншої підгрупи веде обидві підгрупи разом (`swapLessonId` — його пара). */
  | 'MERGE_SUBGROUPS';
export type ChangeStatus = 'DRAFT' | 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'DECLINED' | 'CANCELLED' | 'APPLIED';
export type RoomType = 'CLASSROOM' | 'COMPUTER_LAB' | 'LAB' | 'GYM' | 'SPORTS_GROUND' | 'OTHER';
export type ReasonCode = 'ABSENT' | 'MUTUAL' | 'FINISHED' | 'BUSINESS_TRIP' | 'OTHER';
export type AbsenceReasonCategory = 'SICK_LEAVE' | 'VACATION' | 'BUSINESS_TRIP' | 'PERSONAL' | 'OTHER';
export type AbsenceSource = 'WEB' | 'BOT' | 'ADMIN';
export type AbsenceStatus = 'ACTIVE' | 'CANCELLED';
export type OrderKind = 'ORDER' | 'AMENDMENT';
export type OrderStatus = 'DRAFT' | 'PUBLISHED';
export type OrderRowType =
  | 'REPLACEMENT'
  | 'CANCELLATION'
  | 'ADDITIONAL'
  | 'BLOCK'
  | 'FINISHED'
  | 'AS_SCHEDULED'
  | 'ROOM_CHANGE'
  /** Round 7: «Об'єднання підгруп: {to.discipline} ({to.teacher}) — обидві підгрупи». */
  | 'MERGE_SUBGROUPS';
export type TimetableVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type TimetableVersionSource = 'MANUAL' | 'IMPORT' | 'GENERATED' | 'COPY';
export type GenerationJobStatus = 'QUEUED' | 'RUNNING' | 'SOLVED' | 'FAILED' | 'CANCELLED';

/**
 * Чому задача з розкладом зупинилась (contracts `GenerationStopReason`): розклад перестав покращуватися, вичерпано
 * старий фіксований ліміт, спрацювала межа часу сервера, диспетчер зупинив (найкращий варіант збережено).
 */
export type GenerationStopReason = 'NO_IMPROVEMENT' | 'TIME_LIMIT' | 'SAFETY_CAP' | 'STOPPED';
export type ControlForm = 'EXAM' | 'CREDIT' | 'NONE';
export type LoadSource = 'MANUAL' | 'CURRICULUM' | 'PROGRAM' | 'TIMETABLE_DERIVED';
export type AccountingStatus = 'FINISHED' | 'WILL_OVERRUN' | 'WILL_UNDERRUN' | 'ON_TRACK';
/** Звідки план годин запису навантаження (рішення власника 15.11.2026). */
export type PlanSource = 'AUTO' | 'FIXED';
export type PreferenceKind = 'UNWANTED' | 'UNAVAILABLE';
/**
 * Старий вид періоду (enum core до довідника видів). @deprecated Лишається один реліз: core досі пише його в
 * `CalendarPeriodDto.kind` і приймає в запиті, але поведінку визначає `periodKind.effect`.
 */
export type CalendarPeriodKind = 'HOLIDAY' | 'NO_CLASSES' | 'PRACTICE' | 'STUDY_MODE' | 'EXAM_SESSION';
/**
 * Що період робить із парами (закритий список у core; пріоритет при накладанні — у порядку переліку):
 * DAY_OFF — вихідний, пар немає; SUSPEND — група не на парах (практика, НС), викладачі працюють;
 * ONLINE — пари лишаються онлайн чи змішано; INFO — лише позначка.
 */
export type PeriodEffect = 'DAY_OFF' | 'SUSPEND' | 'ONLINE' | 'INFO';
/** Звідки період: вручну з адмінки чи з імпорту графіка освітнього процесу. */
export type PeriodSource = 'MANUAL' | 'IMPORT';
export type StudyMode = 'OFFLINE' | 'ONLINE' | 'MIXED';
export type PeriodScope = 'ALL' | 'COURSES' | 'GROUPS';
export type Employment = 'STAFF' | 'PART_TIME';
/**
 * Що отримує викладач, якого призначили на заміну (DOMAIN §7): NOTIFY — заміна діє одразу, йому повідомляють
 * і він відповідає «Добре» / «Не можу» (типовий); CONFIRM — прохання, заміна чекає згоди; AUTO — нічого.
 */
export type ApprovalMode = 'NOTIFY' | 'CONFIRM' | 'AUTO';
export type ConflictKind = 'TEACHER' | 'GROUP' | 'ROOM' | 'CAPACITY' | 'ROOM_TYPE' | 'SHIFT' | 'UNAVAILABLE';
export type ConflictSeverity = 'HARD' | 'SOFT';
export type EntityType = 'GROUP' | 'TEACHER' | 'ROOM';
export type Role = 'ADMIN' | 'TEACHER' | 'SERVICE';
export type ShiftNumber = 1 | 2;
export type ReplyDecision = 'CONFIRM' | 'DECLINE';

// ───────────────────────── Спільні DTO ─────────────────────────

export interface Ref {
  id: Uuid;
  label: string;
}

export interface GroupRef {
  id: Uuid;
  code: string;
  course: number;
}

export interface TeacherRef {
  id: Uuid;
  shortName: string;
  displayName: string;
}

export interface DisciplineRef {
  id: Uuid;
  shortName: string;
  name: string;
  /** Колір дисципліни з каталогу (hex) або null — тоді колір виводиться детерміновано з id. */
  color: Nullable<string>;
}

export interface RoomRef {
  id: Uuid;
  name: string;
  type: RoomType;
}

export interface Conflict {
  kind: ConflictKind;
  severity: ConflictSeverity;
  message: string;
  lessonIds: Uuid[];
  date?: Nullable<IsoDate>;
  weekday?: Nullable<Weekday>;
  pair: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface FieldErrorDto {
  field: string;
  message: string;
}

/** Рядок `problems[]` у 422 `GENERATION_LOAD_EXCEEDS_SLOTS` (genfix §3.6): навантаження не вміщується в сітку. */
export interface LoadProblemDto {
  kind: 'GROUP' | 'TEACHER' | 'ROOM_TYPE';
  id: string;
  label: string;
  /** Пар на тиждень (може бути 18,5 — половинки). */
  requiredPairs: number;
  availablePairs: number;
  message: string;
}

/** RFC 9457 `application/problem+json`. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: FieldErrorDto[];
  conflicts?: Conflict[];
  /** Код бізнес-правила (лише 422): `ABSENCE_STARTED`, `teacher-not-linked` … */
  code?: string;
  /** Ідентифікатор запиту для пошуку в журналах (є в усіх сервісів). */
  requestId?: string;
  /** Лише в 404 `history-not-public`: з якої дати гість бачить заміни й розклад (як у meta). */
  publicHistoryFrom?: IsoDate;
  /** Лише в 422 `GENERATION_LOAD_EXCEEDS_SLOTS` (genfix §3.6): що саме не вміщується в сітку. */
  problems?: LoadProblemDto[];
}

export interface PageQuery {
  page?: number;
  size?: number;
  /** `name,asc` */
  sort?: string;
  q?: string;
}
