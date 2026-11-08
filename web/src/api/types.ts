/**
 * TypeScript-типи для DTO з docs/CONTRACTS.md (REST + SSE).
 *
 * Правила:
 * - Поля й назви збігаються з контрактом буквально (camelCase, ISO-дати, `HH:mm`, UUID-рядки).
 * - Поле з `?` у контракті тут теж необовʼязкове й може прийти як `null` (Jackson пише null).
 * - Де контракт не описує поля (CRUD каталогу, частина адмін-відповідей), тип виведено з
 *   docs/DOMAIN.md і позначено `@inferred`. Агент, що володіє цією областю, може уточнити саме цей блок.
 */

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
  | 'DISCIPLINE_FINISHED';
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
  | 'ROOM_CHANGE';
export type TimetableVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type TimetableVersionSource = 'MANUAL' | 'IMPORT' | 'GENERATED' | 'COPY';
export type GenerationJobStatus = 'QUEUED' | 'RUNNING' | 'SOLVED' | 'FAILED' | 'CANCELLED';
export type ControlForm = 'EXAM' | 'CREDIT' | 'NONE';
export type LoadSource = 'MANUAL' | 'CURRICULUM' | 'PROGRAM' | 'TIMETABLE_DERIVED';
export type AccountingStatus = 'FINISHED' | 'WILL_OVERRUN' | 'WILL_UNDERRUN' | 'ON_TRACK';
export type PreferenceKind = 'UNWANTED' | 'UNAVAILABLE';
export type CalendarPeriodKind = 'HOLIDAY' | 'NO_CLASSES' | 'PRACTICE' | 'STUDY_MODE' | 'EXAM_SESSION';
export type StudyMode = 'OFFLINE' | 'ONLINE' | 'MIXED';
export type PeriodScope = 'ALL' | 'COURSES' | 'GROUPS';
export type Employment = 'STAFF' | 'PART_TIME';
export type ApprovalMode = 'AUTO' | 'CONFIRM';
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
}

export interface PageQuery {
  page?: number;
  size?: number;
  /** `name,asc` */
  sort?: string;
  q?: string;
}

// ───────────────────────── 1. Public (без входу) ─────────────────────────

export interface SemesterRef {
  id: Uuid;
  name: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
}

export interface PairTimeDto {
  number: number;
  startsAt: IsoTime;
  endsAt: IsoTime;
  /** Є лише в `currentPair`. */
  minutesLeft?: number;
}

/**
 * GET /api/public/meta. `semester`, `weekNumber`, `parity`, `bellProfile` — null, коли семестру немає або
 * сьогодні між семестрами (парність поза семестром не вигадуємо); `nextPair` без `minutesLeft`.
 */
export interface PublicMetaDto {
  collegeName: string;
  semester: Nullable<SemesterRef>;
  today: IsoDate;
  weekNumber: Nullable<number>;
  parity: Nullable<WeekParity>;
  studyDay: boolean;
  currentPair: Nullable<PairTimeDto>;
  nextPair: Nullable<PairTimeDto>;
  bellProfile: Nullable<{ id: Uuid; name: string }>;
}

/** GET /api/public/search?q=&limit=10 */
export interface SearchResultDto {
  type: EntityType;
  id: Uuid;
  label: string;
  sublabel: Nullable<string>;
  url: string;
}

/** GET /api/public/groups (лише активні). */
export interface PublicGroupDto {
  id: Uuid;
  code: string;
  course: number;
  specialty: { letters: string; name: string };
  /** Назва відділення; null — у спеціальності його не вказано. */
  department: Nullable<string>;
  shift: ShiftNumber;
}

/** GET /api/public/teachers (лише активні). */
export interface PublicTeacherDto {
  id: Uuid;
  shortName: string;
  displayName: string;
  cycleCommission: Nullable<string>;
  /** Повне імʼя — лише коли налаштування `showTeacherFullNames` це дозволяє і імʼя відоме. */
  fullName: Nullable<string>;
}

/** GET /api/public/rooms */
export interface PublicRoomDto {
  id: Uuid;
  name: string;
  type: RoomType;
  capacity: Nullable<number>;
  building: Nullable<string>;
  /** Скільки груп займаються в аудиторії одночасно (спортзал «с.з.» приймає кілька). */
  maxParallel: number;
}

export interface BellSlotDto {
  pair: number;
  startsAt: IsoTime;
  endsAt: IsoTime;
}

export interface BellProfileSlotDto extends BellSlotDto {
  weekday: Weekday;
}

/** Профіль дзвінків (адмінка) і список профілів у /api/public/bells без дати. */
export interface BellProfileDto {
  id: Uuid;
  name: string;
  active: boolean;
  defaultProfile: boolean;
  slots: BellProfileSlotDto[];
  version: number;
}

/** GET /api/public/bells?date= (404, коли профілю дзвінків немає). */
export interface BellsDto {
  profile: { id: Uuid; name: string };
  /** Дата, на яку дзвінки; null — запит без дати. */
  date: Nullable<IsoDate>;
  /** ISO 1..7: на вихідних `slots` порожні. */
  weekday: number;
  studyDay: boolean;
  slots: BellSlotDto[];
  /** Активні профілі зі слотами — лише без `date`. */
  profiles: Nullable<BellProfileDto[]>;
}

export interface ScheduleOwner {
  type: EntityType;
  id: Uuid;
  label: string;
}

export interface LessonOriginal {
  discipline: Nullable<DisciplineRef>;
  teacher: Nullable<TeacherRef>;
  room: Nullable<RoomRef>;
  /** Пара, з якої перенесли заняття (лише MOVED). */
  pair: Nullable<number>;
}

export interface LessonView {
  id: Uuid;
  pair: number;
  /** null — не налаштовано профіль дзвінків (час пари невідомий). */
  startsAt: Nullable<IsoTime>;
  /** null — без профілю дзвінків; для BLOCK — кінець останньої пари блоку. */
  endsAt: Nullable<IsoTime>;
  discipline: DisciplineRef;
  teacher: Nullable<TeacherRef>;
  room: Nullable<RoomRef>;
  group: GroupRef;
  /** 0 — уся група, 1..n — підгрупа. */
  subgroup: number;
  kind: LessonViewKind;
  online: boolean;
  original: Nullable<LessonOriginal>;
  /** Надрукована причина розпорядження; null для ADDITIONAL, BLOCK і заміни аудиторії. */
  reasonText: Nullable<string>;
  changeId: Nullable<Uuid>;
}

export interface DayDto {
  date: IsoDate;
  weekday: Weekday;
  /** Великими літерами, як у розкладі: «ПОНЕДІЛОК», «ПʼЯТНИЦЯ». Для показу — `weekdayTitle()`. */
  weekdayLabel: string;
  /** null — дата поза семестром. */
  parity: Nullable<WeekParity>;
  studyDay: boolean;
  note: Nullable<string>;
  lessons: LessonView[];
}

/** GET /api/public/schedule/{group|teacher|room}/{id}?from=&to= (не більше 31 дня) */
export interface ScheduleDto {
  owner: ScheduleOwner;
  from: IsoDate;
  to: IsoDate;
  days: DayDto[];
}

/**
 * Рядок публічних змін. Контракт не уточнює тип `discipline`/`teacher` усередині `from`/`to`;
 * трактуємо їх як текстовий знімок, як у рядках розпорядження (OrderItemDto). @inferred
 */
export interface ChangeView {
  pairLabel: string;
  rowType: OrderRowType;
  from: { discipline: Nullable<string>; teacher: Nullable<string> };
  reasonText: Nullable<string>;
  to: { discipline: Nullable<string>; teacher: Nullable<string> };
  room: Nullable<string>;
  subgroupNote: Nullable<string>;
}

/** GET /api/public/changes?date= */
export interface PublicChangesDto {
  date: IsoDate;
  /** null — дата поза семестром. */
  parity: Nullable<WeekParity>;
  /** «Понеділок» (з великої літери, на відміну від DayDto). */
  weekdayLabel: string;
  order: Nullable<{ id: Uuid; number: Nullable<number>; publishedAt: Nullable<IsoInstant> }>;
  /** Рядок групи, якої немає в каталозі, має `group.id = null`. */
  groups: Array<{ group: Omit<GroupRef, 'id'> & { id: Nullable<Uuid> }; items: ChangeView[] }>;
}

/** GET /api/public/invites/{token} */
export interface InviteInfoDto {
  valid: boolean;
  teacherName: string;
  expiresAt: IsoInstant;
}

/** POST /api/public/invites/{token}/accept → 201 */
export interface AcceptInviteRequest {
  username: string;
  password: string;
}

export interface AcceptInviteResponse {
  username: string;
}

// ───────────────────────── 2. Admin: каталог (@inferred з DOMAIN §3) ─────────────────────────

/** @inferred */
export interface DepartmentDto {
  id: Uuid;
  name: string;
  shortName: Nullable<string>;
}

/** @inferred */
export interface SpecialtyDto {
  id: Uuid;
  code: string;
  letters: string;
  name: string;
  departmentId: Uuid;
}

/** @inferred */
export interface CycleCommissionDto {
  id: Uuid;
  name: string;
}

/** @inferred */
export interface TeacherDto {
  id: Uuid;
  lastName: string;
  firstName: Nullable<string>;
  middleName: Nullable<string>;
  shortName: string;
  displayName: string;
  cycleCommission: Nullable<Ref>;
  employment: Employment;
  email: Nullable<string>;
  active: boolean;
  needsReview: boolean;
  /** Чи привʼязаний обліковий запис Keycloak. */
  linked?: boolean;
  version?: number;
}

/** @inferred */
export interface AliasDto {
  id: Uuid;
  alias: string;
}

/** @inferred */
export interface DisciplineDto {
  id: Uuid;
  name: string;
  shortName: string;
  color: Nullable<string>;
  active: boolean;
  needsReview: boolean;
  version?: number;
}

/** @inferred */
export interface BuildingDto {
  id: Uuid;
  name: string;
}

/** @inferred */
export interface RoomDto {
  id: Uuid;
  building: Nullable<Ref>;
  name: string;
  type: RoomType;
  capacity: Nullable<number>;
  maxParallel: number;
  active: boolean;
  version?: number;
}

/** @inferred */
export interface SubgroupDto {
  id: Uuid;
  number: number;
  name: Nullable<string>;
  size: Nullable<number>;
}

/** @inferred */
export interface GroupDto {
  id: Uuid;
  code: string;
  course: number;
  specialty: Nullable<Ref>;
  number: Nullable<number>;
  size: Nullable<number>;
  shift: ShiftNumber;
  curator: Nullable<TeacherRef>;
  homeRoom: Nullable<RoomRef>;
  active: boolean;
  subgroups?: SubgroupDto[];
  version?: number;
}

// ───────────────────────── 2. Admin: календар ─────────────────────────

/** @inferred */
export interface SemesterDto {
  id: Uuid;
  academicYearId?: Nullable<Uuid>;
  number: number;
  name: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  firstWeekParity: WeekParity;
  weeksCount: Nullable<number>;
  bellProfileId: Nullable<Uuid>;
  active: boolean;
}

/** GET /api/admin/semesters/{id}/weeks @inferred */
export interface SemesterWeekDto {
  weekStart: IsoDate;
  weekNumber: number;
  parity: WeekParity;
  overridden: boolean;
}

/** @inferred */
export interface CalendarPeriodDto {
  id: Uuid;
  semesterId: Uuid;
  kind: CalendarPeriodKind;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  studyMode: Nullable<StudyMode>;
  orderNote: Nullable<string>;
  scope: PeriodScope;
  courses: Nullable<number[]>;
  groupIds: Nullable<Uuid[]>;
}

/** GET/PUT /api/admin/shift-windows */
export interface ShiftWindowDto {
  shift: ShiftNumber;
  weekday: Weekday;
  firstPair: number;
  lastPair: number;
}

// ───────────────────────── 2. Admin: навантаження ─────────────────────────

export interface LoadItemTeacherDto {
  subgroup: number;
  teacher: TeacherRef;
}

/** /api/admin/load */
export interface LoadItemDto {
  id: Uuid;
  semesterId: Uuid;
  group: GroupRef;
  discipline: DisciplineRef;
  lessonKind: LessonKind;
  hoursPerWeek: number;
  plannedHours: number;
  splitSubgroups: boolean;
  teachers: LoadItemTeacherDto[];
  requiredRoomType: Nullable<RoomType>;
  preferredRoom: Nullable<RoomRef>;
  controlForm: ControlForm;
  source: LoadSource;
  /** Розкладка на тиждень: цілі пари і половинки (чисельник/знаменник). */
  pattern: { whole: number; half: number };
}

// ───────────────────────── 2. Admin: розклад ─────────────────────────

export interface MetricsDto {
  groupWindows: number;
  teacherWindows: number;
  brokenDays: number;
  latePairs: number;
  maxPairsPerDay: number;
  /** @inferred — склад рядка контракт не описує */
  perGroup: Array<{
    group: GroupRef;
    windows: number;
    brokenDays: number;
    latePairs: number;
    maxPairsPerDay: number;
  }>;
}

/** GET /api/admin/timetable/versions?semesterId (адитивно: semesterId, publishedBy, generationJobId, notes, createdAt). */
export interface TimetableVersionDto {
  id: Uuid;
  semesterId: Uuid;
  name: string;
  status: TimetableVersionStatus;
  source: TimetableVersionSource;
  effectiveFrom: Nullable<IsoDate>;
  publishedAt: Nullable<IsoInstant>;
  publishedBy: Nullable<string>;
  generationJobId: Nullable<Uuid>;
  notes: Nullable<string>;
  createdAt: IsoInstant;
  lessonCount: number;
  hardConflicts: number;
  softWarnings: number;
  metrics: Nullable<MetricsDto>;
}

export interface CreateVersionRequest {
  semesterId: Uuid;
  name: string;
  copyFromVersionId?: Nullable<Uuid>;
}

export interface TemplateLessonDto {
  id: Uuid;
  weekday: Weekday;
  pair: number;
  parity: Parity;
  group: GroupRef;
  subgroup: number;
  discipline: DisciplineRef;
  teacher?: Nullable<TeacherRef>;
  room?: Nullable<RoomRef>;
  lessonKind: LessonKind;
  loadItemId?: Nullable<Uuid>;
  pinned: boolean;
  online: boolean;
  streamId?: Nullable<Uuid>;
}

/** Тіло POST/PUT уроку чернетки. @inferred */
export interface TemplateLessonRequest {
  weekday: Weekday;
  pair: number;
  parity: Parity;
  groupId: Uuid;
  subgroup: number;
  disciplineId: Uuid;
  teacherId?: Nullable<Uuid>;
  roomId?: Nullable<Uuid>;
  lessonKind: LessonKind;
  loadItemId?: Nullable<Uuid>;
  pinned?: boolean;
  online?: boolean;
  streamId?: Nullable<Uuid>;
}

/** Відповідь на редагування чернетки: урок + конфлікти зачепленого слота. @inferred */
export interface TemplateLessonMutationResponse {
  lesson: TemplateLessonDto;
  conflicts: Conflict[];
}

/** POST …/lessons/{lessonId}/move (`?dryRun=true` — перевірка для DnD) */
export interface MoveLessonRequest {
  weekday: Weekday;
  pair: number;
  parity: Parity;
  roomId?: Nullable<Uuid>;
}

/** GET …/versions/{id}/conflicts */
export interface ConflictsDto {
  hard: Conflict[];
  soft: Conflict[];
}

/** GET …/versions/{a}/diff/{b} */
export interface VersionDiffDto {
  added: TemplateLessonDto[];
  removed: TemplateLessonDto[];
  moved: Array<{ from: TemplateLessonDto; to: TemplateLessonDto }>;
}

export interface PublishVersionRequest {
  effectiveFrom: IsoDate;
}

/**
 * POST …/publish → 200. `keptLessons` — заняття з чинних змін, що лишились; `coveredByChanges` — заняття шаблону,
 * яких не створено, бо їх закриває зміна; `conflicts` — HARD-конфлікти лишених змін з новою версією.
 */
export interface PublishVersionResponse {
  versionId: Uuid;
  effectiveFrom: IsoDate;
  materializedLessons: number;
  keptLessons: number;
  coveredByChanges: number;
  conflicts: Conflict[];
}

// ───────────────────────── 2. Admin: генерація ─────────────────────────

export interface GenerationWeights {
  windows: number;
  brokenDays: number;
  latePairs: number;
  preferences: number;
  homeRoom: number;
}

/** POST /api/admin/generation/jobs → 202 {jobId} */
export interface GenerationJobRequest {
  semesterId: Uuid;
  scope: { groupIds?: Uuid[]; courses?: number[] };
  baseVersionId?: Nullable<Uuid>;
  /** 10..600 */
  timeLimitSeconds: number;
  weights?: Nullable<GenerationWeights>;
}

export interface GenerationJobCreated {
  jobId: Uuid;
}

export interface GenerationProgressPoint {
  /** секунди від старту */
  t: number;
  hard: number;
  soft: number;
}

export interface ConstraintAnalysisDto {
  constraint: string;
  matchCount: number;
  score: string;
}

/**
 * GET /api/admin/generation/jobs[/{id}] (список без `semesterId` — останні 50). Адитивно до контракту:
 * `semesterId`, `createdAt`, `baseVersionId`, `timeLimitSeconds`, `requestedBy`.
 */
export interface GenerationJobDto {
  id: Uuid;
  semesterId: Uuid;
  status: GenerationJobStatus;
  createdAt: IsoInstant;
  startedAt: Nullable<IsoInstant>;
  finishedAt: Nullable<IsoInstant>;
  /** `"{hard}hard/{soft}soft"`, напр. `0hard/-42soft`; null — ще немає повного розкладу. */
  bestScore: Nullable<string>;
  progress: GenerationProgressPoint[];
  resultVersionId: Nullable<Uuid>;
  baseVersionId: Nullable<Uuid>;
  /** Ліміт часу запуску, 10..600 с. */
  timeLimitSeconds: Nullable<number>;
  analysis: Nullable<ConstraintAnalysisDto[]>;
  metricsBefore: Nullable<MetricsDto>;
  metricsAfter: Nullable<MetricsDto>;
  error: Nullable<string>;
  requestedBy: Nullable<string>;
}

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
}

export interface OptionDto {
  kind: ChangeKind;
  /** 0..100 */
  score: number;
  title: string;
  reasons: string[];
  teacher: Nullable<TeacherRef>;
  discipline: Nullable<DisciplineRef>;
  room: Nullable<RoomRef>;
  swapLesson: Nullable<LessonView>;
  warnings: string[];
  /** Адитивно: дата й пара відпрацювання (лише ADDITIONAL). */
  date: Nullable<IsoDate>;
  pair: Nullable<number>;
}

/** GET /api/admin/lessons/{lessonId}/options */
export interface LessonOptionsDto {
  lesson: LessonView;
  options: OptionDto[];
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
  pending: number;
  confirmed: number;
  declined: number;
  orderDraft: Nullable<{ id: Uuid; itemCount: number }>;
  generation: Nullable<{ status: GenerationJobStatus }>;
  /** Завжди null: витрати ШІ рахує importer (`GET /api/import/ai/usage`). */
  aiSpendToday: Nullable<number>;
  alerts: DashboardAlert[];
}

// ───────────────────────── 2. Admin: розпорядження ─────────────────────────

/** Рядок списку GET /api/admin/orders (Page) */
export interface OrderSummaryDto {
  id: Uuid;
  number: Nullable<number>;
  date: IsoDate;
  kind: OrderKind;
  status: OrderStatus;
  itemCount: number;
  publishedAt: Nullable<IsoInstant>;
}

export interface OrderItemDto {
  id: Uuid;
  groupCode: string;
  pairLabel: string;
  rowType: OrderRowType;
  fromDiscipline: Nullable<string>;
  fromTeacher: Nullable<string>;
  reasonText: Nullable<string>;
  toDiscipline: Nullable<string>;
  toTeacher: Nullable<string>;
  roomText: Nullable<string>;
  changeId: Nullable<Uuid>;
}

export interface OrderDto {
  id: Uuid;
  number: Nullable<number>;
  date: IsoDate;
  kind: OrderKind;
  parentId: Nullable<Uuid>;
  status: OrderStatus;
  /** «РОЗПОРЯДЖЕННЯ» (чернетка), «РОЗПОРЯДЖЕННЯ №35», «ДОПОВНЕННЯ до розпорядження №35» (власний номер — `number`). */
  title: string;
  weekdayLabel: string;
  parityLabel: string;
  items: OrderItemDto[];
  roomChanges: OrderItemDto[];
  notes: string[];
  signatoryPosition: string;
  signatoryName: string;
  /** Адитивні поля core: рядок під заголовком («По коледжу про деякі зміни в розкладі занять»). */
  subtitle: string;
  /** «На 19 жовтня 2026 р.» */
  dateLabel: string;
  publishedAt: Nullable<IsoInstant>;
  publishedBy: Nullable<string>;
  version: number;
}

export interface UpdateOrderNotesRequest {
  notes: string[];
}

// ───────────────────────── 2. Admin: облік годин ─────────────────────────

/** @inferred — формат пропозиції контракт не описує */
export interface AccountingSuggestion {
  kind: 'DISCIPLINE_FINISHED' | 'ADDITIONAL';
  text: string;
  fromDate?: Nullable<IsoDate>;
  lessons?: Nullable<number>;
}

/** GET /api/admin/accounting?semesterId&groupId */
export interface AccountingRowDto {
  group: GroupRef;
  discipline: DisciplineRef;
  plan: number;
  fact: number;
  remaining: number;
  forecast: number;
  status: AccountingStatus;
  forecastFinishDate?: Nullable<IsoDate>;
  suggestion?: Nullable<AccountingSuggestion>;
}

/** GET /api/admin/accounting/teachers?month=2026-10 */
export interface TeacherStatsDto {
  teacher: TeacherRef;
  substitutionsCount: number;
  substitutionsHours: number;
  missedCount: number;
}

// ───────────────────────── 2. Admin: ідентичність і налаштування ─────────────────────────

export interface CreateInviteRequest {
  teacherId: Uuid;
}

export interface InviteCreatedDto {
  token: string;
  url: string;
  expiresAt: IsoInstant;
}

/** Елемент GET /api/admin/invites. @inferred */
export interface InviteDto {
  id: Uuid;
  teacher: TeacherRef;
  url?: Nullable<string>;
  expiresAt: IsoInstant;
  usedAt: Nullable<IsoInstant>;
  createdAt: Nullable<IsoInstant>;
}

/** GET/PUT /api/admin/settings (межі — SETTINGS_LIMITS у api/settings-limits.ts, як у core SystemSettings). */
export interface SettingsDto {
  approvalMode: ApprovalMode;
  confirmTimeoutMinutes: number;
  signatoryPosition: string;
  signatoryName: string;
  collegeName: string;
  hoursPerPair: number;
  showTeacherFullNames: boolean;
  activeBellProfileId: Nullable<Uuid>;
}

// ───────────────────────── 2. Admin: імпорт у core (ADMIN або SERVICE) ─────────────────────────

/** POST /api/admin/import/catalog — upsert за природними ключами. Вміст елементів @inferred. */
export interface ImportCatalogRequest {
  departments: Array<Record<string, unknown>>;
  specialties: Array<Record<string, unknown>>;
  teachers: Array<Record<string, unknown>>;
  disciplines: Array<Record<string, unknown>>;
  rooms: Array<Record<string, unknown>>;
  groups: Array<Record<string, unknown>>;
  aliases: Array<Record<string, unknown>>;
}

export interface ImportCatalogResult {
  created: number;
  updated: number;
  ids: Record<string, unknown>;
}

export interface ImportTimetableLesson {
  weekday: Weekday;
  pair: number;
  parity: Parity;
  groupCode: string;
  subgroup: number;
  disciplineShortName: string;
  teacherShortName?: Nullable<string>;
  roomName?: Nullable<string>;
  online: boolean;
}

/** POST /api/admin/import/timetable → id чернетки */
export interface ImportTimetableRequest {
  semesterId: Uuid;
  name: string;
  lessons: ImportTimetableLesson[];
}

/** @inferred — контракт каже лише «draft version id» */
export interface ImportTimetableResult {
  versionId: Uuid;
}

export interface ImportLoadRequest {
  semesterId: Uuid;
  items: Array<Record<string, unknown>>;
}

export interface ImportChangesRequest {
  date: IsoDate;
  items: Array<Record<string, unknown>>;
}

// ───────────────────────── 3. Teacher ─────────────────────────

/** GET /api/me */
export interface MeDto {
  userId: Uuid;
  username: string;
  roles: Role[];
  teacher?: Nullable<TeacherRef>;
}

/** POST /api/me/absences (без teacherId — сервер бере з токена) @inferred */
export type MyAbsenceRequest = Omit<AbsenceRequest, 'teacherId'>;

/** POST /api/me/changes/{id}/confirm|decline */
export interface ChangeReplyRequest {
  comment?: Nullable<string>;
}

/** GET/PUT /api/me/preferences?semesterId */
export interface PreferenceDto {
  weekday: Weekday;
  pair: number;
  kind: PreferenceKind;
  note?: Nullable<string>;
}

// ───────────────────────── 4. Internal (SERVICE) — SPA не викликає, типи для повноти ─────────────────────────

export interface InternalChangeReplyRequest {
  teacherId: Uuid;
  decision: ReplyDecision;
  comment?: Nullable<string>;
}

export interface InternalAbsenceRequest extends AbsenceRequest {
  source: 'BOT';
}

export interface CatalogSnapshotDto {
  groups: Array<{ id: Uuid; code: string; course: number; subgroups: number[] }>;
  teachers: Array<{ id: Uuid; shortName: string; displayName: string; lastName: string; aliases: string[] }>;
  disciplines: Array<{ id: Uuid; shortName: string; name: string; aliases: string[] }>;
  rooms: Array<{ id: Uuid; name: string; type: RoomType }>;
  semesters: SemesterDto[];
}

// ───────────────────────── 5. Importer ─────────────────────────

export type ImportKind = 'TIMETABLE_XLSX' | 'DISCIPLINE_PROGRAM' | 'CURRICULUM' | 'ORDER_DOCUMENT';
export type ImportJobStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'NEEDS_REVIEW'
  | 'READY'
  | 'APPLYING'
  | 'APPLIED'
  | 'FAILED';
export type ReportSeverity = 'OK' | 'INFO' | 'WARN' | 'ERROR';
export type MappingType = 'TEACHER' | 'DISCIPLINE' | 'GROUP' | 'ROOM';

export interface ScoredRef {
  id: Uuid;
  label: string;
  score: number;
}

/** Рядок звіту імпорту. importer не пише null-поля (`@JsonInclude(NON_NULL)`): їх просто немає. */
export interface ReportRow {
  severity: ReportSeverity;
  type: string;
  raw: string;
  matched?: ScoredRef;
  suggestions: ScoredRef[];
  location: string;
  /** Пояснення українською (адитивне поле importer). */
  message?: string;
}

/** Формат файлу за вмістом (магічні байти), а не за назвою. */
export type ImportFileType = 'XLSX' | 'DOCX' | 'DOC' | 'PDF' | 'JPEG' | 'PNG' | 'WEBP' | 'GIF';

/**
 * ImportJobDto importer: контракт + адитивні `fileType`, `fileSize`, `updatedAt`, `createdBy`, `semesterId`,
 * `options`, `mappings`, `result`, `canApply`. null-поля не пишуться (`NON_NULL`). У списку `report` порожній,
 * а `draft`, `options`, `mappings`, `result` відсутні.
 */
export interface ImportJobDto {
  id: Uuid;
  kind: ImportKind;
  fileName: string;
  fileType: ImportFileType;
  fileSize: number;
  status: ImportJobStatus;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  createdBy?: string;
  semesterId?: Uuid;
  summary: { recognized: number; unknown: number; conflicts: number; warnings: number };
  report: ReportRow[];
  draft?: unknown;
  options?: unknown;
  mappings?: ImportMapping[];
  ai?: { model: string; inputTokens: number; outputTokens: number; costUsd: number };
  result?: unknown;
  error?: string;
  /** Чи можна застосувати зараз (READY або FAILED з чернеткою без блокувальних рядків). */
  canApply: boolean;
}

export interface ImportMapping {
  type: MappingType;
  raw: string;
  targetId?: Nullable<Uuid>;
  createNew?: Nullable<Record<string, unknown>>;
  addAlias: boolean;
}

/** PUT /api/import/jobs/{id}/mappings */
export interface ImportMappingsRequest {
  mappings: ImportMapping[];
}

/** POST /api/import/ai/parse-absence */
export interface ParseAbsenceRequest {
  text: string;
  teacherId?: Nullable<Uuid>;
  today: IsoDate;
}

export interface ParseAbsenceResponse {
  dateFrom: IsoDate;
  dateTo: IsoDate;
  pairs?: Nullable<number[]>;
  reasonCategory: AbsenceReasonCategory;
  confidence: number;
  clarification?: Nullable<string>;
}

/** GET /api/import/ai/usage (адитивно: `remainingUsd`, `enabled`). */
export interface AiUsageDto {
  today: { calls: number; inputTokens: number; outputTokens: number; costUsd: number };
  dailyBudgetUsd: number;
  /** Скільки ще можна витратити сьогодні (не менше 0). */
  remainingUsd: number;
  model: { text: string; vision: string };
  /** Чи налаштовано ШІ (є ключ API). */
  enabled: boolean;
}

// ───────────────────────── 6. Notification ─────────────────────────

export type BotMode = 'POLLING' | 'WEBHOOK';
export type BindingType = 'GROUP_CHAT' | 'TEACHER' | 'ADMIN_CHAT' | 'SUBSCRIBER';

/** GET /api/notify/bot */
export interface BotStatusDto {
  username: string;
  link: string;
  mode: BotMode;
  connected: boolean;
  /** Чому бот не працює (лише коли `connected=false`), адитивне поле notification. */
  problem?: Nullable<string>;
}

/** GET /api/notify/bindings. Адитивно до контракту: `groupCode`, `digestEnabled`. */
/** GET /api/notify/bindings/me — чи привʼязав викладач (власник облікового запису) свій Telegram. */
export interface MyBindingDto {
  teacherId: Uuid;
  teacherName: string;
  bound: boolean;
  binding: Nullable<ChatBindingDto>;
  botUsername: Nullable<string>;
  botLink: Nullable<string>;
}

export interface ChatBindingDto {
  id: Uuid;
  type: BindingType;
  /** Маскований id чату: `-•••7890` (група) або `•••7890` (особистий). */
  chatId: string;
  title: string;
  groupId: Nullable<Uuid>;
  groupCode: Nullable<string>;
  teacherId: Nullable<Uuid>;
  digestEnabled: boolean;
  createdAt: IsoInstant;
}

/** POST /api/notify/bindings/codes */
export interface BindingCodeRequest {
  type: BindingType;
  groupId?: Nullable<Uuid>;
  teacherId?: Nullable<Uuid>;
}

/**
 * POST /api/notify/bindings/codes → 200. Адитивно: `type`, `label` (код групи чи імʼя викладача),
 * `groupLink` (`?startgroup=` — додає бота в групу) і `command` (`/bind XXXX-XXXX`) — лише для чатів, що
 * можуть бути групою (GROUP_CHAT, ADMIN_CHAT). 422 `teacher-not-linked` — обліковий запис викладача не привʼязано.
 */
export interface BindingCodeDto {
  /** Як показувати людині: `ABCD-EFGH`. */
  code: string;
  /** `https://t.me/<bot>?start=<code>` — особистий чат. */
  deepLink: string;
  expiresAt: IsoInstant;
  type: BindingType;
  label: string;
  groupLink: Nullable<string>;
  command: Nullable<string>;
}

export type DeliveryStatus = 'QUEUED' | 'SENT' | 'FAILED' | 'RETRYING';
/** Навіщо поставили повідомлення в чергу (фільтр журналу). */
export type DeliveryPurpose =
  | 'ORDER_GROUP'
  | 'ORDER_TEACHER'
  | 'ORDER_ADMIN'
  | 'SUBSTITUTION_REQUEST'
  | 'SUBSTITUTION_REMINDER'
  | 'SUBSTITUTION_STATUS'
  | 'SUBSTITUTION_ADMIN'
  | 'ABSENCE_ADMIN'
  | 'GENERATION_ADMIN'
  | 'DIGEST'
  | 'TEST';
export type OutboundKind = 'TEXT' | 'PHOTO' | 'EDIT_TEXT';

/** Запис журналу GET /api/notify/deliveries (сторінка `Page<DeliveryDto>`, нові спершу). */
export interface DeliveryDto {
  id: Uuid;
  orderId: Nullable<Uuid>;
  /** Маскований id чату. */
  chatId: string;
  /** Назва чату на момент постановки в чергу; null — тестове повідомлення власнику. */
  chatTitle: Nullable<string>;
  /** null — чат без привʼязки (тестове повідомлення власнику). */
  bindingType: Nullable<BindingType>;
  purpose: DeliveryPurpose;
  kind: OutboundKind;
  status: DeliveryStatus;
  attempts: number;
  /** Остання причина збою. */
  error: Nullable<string>;
  createdAt: IsoInstant;
  sentAt: Nullable<IsoInstant>;
  /** Коли наступна спроба (лише для QUEUED/RETRYING). */
  nextAttemptAt: Nullable<IsoInstant>;
}

export type DocumentFormat = 'docx' | 'pdf' | 'png';

// ───────────────────────── SSE: GET /api/events/stream ─────────────────────────

/** Подія `schedule.changed` (інвалідація кешу). */
export interface ScheduleChangedPayload {
  dates: IsoDate[];
  groupIds: Uuid[];
  teacherIds: Uuid[];
  reason: string;
}

/** Подія `generation.progress`. @inferred: контракт каже «JSON of the related DTO». */
export interface GenerationProgressPayload {
  jobId: Uuid;
  elapsedMs: number;
  hardScore: number;
  softScore: number;
}

export interface ServerEventMap {
  'change.updated': ChangeDto;
  'absence.created': AbsenceSummaryDto;
  'absence.updated': AbsenceSummaryDto;
  'generation.progress': GenerationProgressPayload;
  'generation.completed': GenerationJobDto;
  /** Короткий запис розпорядження (як рядок списку), а не повний OrderDto. */
  'order.published': OrderSummaryDto;
  'schedule.changed': ScheduleChangedPayload;
  ping: unknown;
}

export type ServerEventType = keyof ServerEventMap;

export type ServerEvent = {
  [K in ServerEventType]: { type: K; data: ServerEventMap[K]; id?: string };
}[ServerEventType];
