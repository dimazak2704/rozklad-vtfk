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
  /** Перший навчальний день після сьогодні (вихідні й канікули пропущено); null, якщо такого не видно. */
  nextStudyDay: Nullable<StudyDayDto>;
  /** Серверний момент відповіді (ISO instant). Нові сервери віддають завжди; старі — ні. */
  now?: string;
  /** Лише на тестовому стенді з переведеним часом (ROZKLAD_STAND_TIME); інакше поля немає. */
  standTime?: true;
  /**
   * З якої дати гість (без входу) бачить заміни й розпорядження: сьогодні core мінус `changesHistoryDays`;
   * розклад — з понеділка тижня цієї дати; майбутнє — завжди. Однакове для всіх, персонал (ADMIN, TEACHER з
   * токеном) обмежень не має. Старіший core поля не віддає — тоді сайт нічого не обмежує.
   */
  publicHistoryFrom?: IsoDate;
}

/** Навчальний день з тижнем: `weekday` ISO 1..5. */
export interface StudyDayDto {
  date: IsoDate;
  weekday: number;
  weekNumber: number;
  parity: WeekParity;
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
  /** Імʼя без по батькові — за тим самим правилом, що й `fullName` (старий core поля не має). */
  firstName?: Nullable<string>;
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
  /**
   * Парність рядка шаблону, з якого пара: NUMERATOR чи DENOMINATOR — пара лише по чисельниках чи знаменниках,
   * BOTH — щотижня (і для скасованої пари шаблону). null — разова пара (заміна, додаткова, блок).
   * Старіший core поля не має: тоді картка просто не показує позначку.
   */
  parity?: Nullable<Parity>;
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
  /**
   * Періоди дня, коли група не на парах (SUSPEND: практика, НС…): для групи — ті, що її стосуються (порожньо, якщо
   * день вихідний), для викладача — ті, якими він керує; для аудиторії порожньо. Старіший core поля не має.
   */
  practices?: PracticeDto[];
}

/**
 * Період дня, коли група не на парах (ефект SUSPEND: практика, НС, збори): назва, керівники (може ще не бути),
 * групи або курси. Назва поля й типу — історична (раніше тут була лише практика).
 */
export interface PracticeDto {
  periodId: Uuid;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  supervisors: TeacherRef[];
  groups: GroupRef[];
  courses: number[];
  /** Вид періоду; старіший core поля не має — тоді це практика. */
  kind?: Nullable<{ code: string; name: string; color: Nullable<string>; effect: PeriodEffect }>;
  /** Аудиторія періоду: «ауд. 401»; null — не вказано. */
  room?: Nullable<RoomRef>;
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
  /** Адитивно (R4): номер розпорядження чи доповнення, у якому надруковано рядок. */
  orderNumber?: Nullable<number>;
}

/** Опубліковане розпорядження чи доповнення дати (публічна сторінка замін). */
export interface PublishedOrderRef {
  id: Uuid;
  number: Nullable<number>;
  publishedAt: Nullable<IsoInstant>;
  imported: boolean;
}

/** GET /api/public/changes?date= */
export interface PublicChangesDto {
  date: IsoDate;
  /** null — дата поза семестром. */
  parity: Nullable<WeekParity>;
  /**
   * Адитивно (R6): чи є цього дня заняття в коледжі (пн–пт у семестрі, поза святами й днями без занять
   * для всіх). `false` — розклад цього дня не діє, тож «розпорядження ще немає» не означає «заняття як зазвичай».
   */
  studyDay: boolean;
  /** «Понеділок» (з великої літери, на відміну від DayDto). */
  weekdayLabel: string;
  /** `imported` — паперове розпорядження, внесене в систему як текст: файлів (PDF/PNG) у нього немає, `publishedAt` — час імпорту. */
  order: Nullable<PublishedOrderRef>;
  /** Адитивно (R4): опубліковані доповнення дати за номером — у кожного свій PDF/PNG. */
  amendments?: PublishedOrderRef[];
  /** Рядок групи, якої немає в каталозі, має `group.id = null`. */
  groups: Array<{ group: Omit<GroupRef, 'id'> & { id: Nullable<Uuid> }; items: ChangeView[] }>;
}

/** GET /api/public/invites/{token} — і запрошення викладача, і посилання для пароля (`kind`). */
export interface InviteInfoDto {
  valid: boolean;
  /** Ім'я людини: викладача (запрошення) або власника облікового запису (посилання для пароля). */
  teacherName: string;
  expiresAt: IsoInstant;
  status?: 'ACTIVE' | 'USED' | 'EXPIRED' | 'REVOKED';
  /** INVITE — реєстрація викладача; PASSWORD — новий пароль наявного облікового запису. */
  kind?: 'INVITE' | 'PASSWORD';
  /** Логін облікового запису для посилання пароля (сторінка показує його без змін); інакше null. */
  username?: Nullable<string>;
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
  /** Чи привʼязаний обліковий запис (сервіс входу auth). */
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

/** Вид періоду коротко: у періоді календаря, у блоці дня. */
export interface PeriodKindRef {
  id: Uuid;
  /** Код як у графіку коледжу: «НС», «К», «НП». */
  code: string;
  name: string;
  /** `#RRGGBB` з легенди графіка або null. */
  color: Nullable<string>;
  effect: PeriodEffect;
}

/** GET /api/admin/period-kinds — вид періоду з довідника. */
export interface PeriodKindDto {
  id: Uuid;
  code: string;
  name: string;
  effect: PeriodEffect;
  color: Nullable<string>;
  /** Інші написання коду (у легенді «З», у клітинках «ЗУ»). */
  aliases: string[];
  /** Шаблон примітки для розпорядження: `{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]`. */
  noteTemplate: Nullable<string>;
  /** Вбудований: код не змінюється, видалити не можна (лише вимкнути). */
  builtin: boolean;
  active: boolean;
  position: number;
  /** Скільки періодів календаря цього виду. */
  usage: number;
  version: number;
}

/** POST/PUT /api/admin/period-kinds */
export interface PeriodKindRequest {
  code: string;
  name: string;
  effect: PeriodEffect;
  color?: Nullable<string>;
  aliases?: string[];
  noteTemplate?: Nullable<string>;
  active?: boolean;
  position?: number;
  version?: number;
}

/** PUT /api/admin/period-kinds/{id}?dryRun=true — що зачепить зміна виду (нічого не зберігається). */
export interface PeriodKindImpactDto {
  /** Вид, яким він став би після збереження. */
  kind: PeriodKindDto;
  effectChanged: boolean;
  /** Скільки періодів цього виду. */
  periods: number;
  /** Навчальні дні (пн–пт) усіх періодів виду в межах їхніх семестрів. */
  dates: IsoDate[];
  groups: GroupRef[];
  lessons: { added: number; removed: number; updated: number };
  /** Живі заміни, які зміна зачепила б (як 409 правки періоду). */
  conflicts: Conflict[];
  /** Чому зміну не можна зберегти (тексти 409). */
  blockers: string[];
  canApply: boolean;
}

/**
 * GET/POST/PUT /api/admin/calendar-periods. Поведінку періоду визначає `periodKind.effect`; `kind` — старий
 * вид (@deprecated, ще один реліз). Поля довідника видів адитивні: старіший core їх не має.
 */
export interface CalendarPeriodDto {
  id: Uuid;
  semesterId: Uuid;
  /** @deprecated Старий вид: для К/БЗ/НП/ФН/І — свій, для решти — за ефектом. Читайте `periodKind`. */
  kind: CalendarPeriodKind;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  studyMode: Nullable<StudyMode>;
  orderNote: Nullable<string>;
  scope: PeriodScope;
  courses: Nullable<number[]>;
  groupIds: Nullable<Uuid[]>;
  /** Керівники (лише для виду з ефектом SUSPEND); старіший core полів не має. */
  supervisorIds?: Uuid[];
  supervisors?: TeacherRef[];
  kindId?: Uuid;
  periodKind?: PeriodKindRef;
  /** Аудиторія періоду (лише SUSPEND, «ауд. 401» у блоці дня); у кроці 1 лише показується. */
  roomId?: Nullable<Uuid>;
  room?: Nullable<RoomRef>;
  source?: PeriodSource;
  /** Ключ рядка графіка, з якого імпортовано період. */
  sourceKey?: Nullable<string>;
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
  /** За семестр. AUTO — рахує система за днями, коли група на теорії; FIXED — число задали вручну чи імпортом. */
  plannedHours: number;
  planSource: PlanSource;
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
  /** Вікна викладачів у межах однієї зміни; очікування між змінами — окремо (`teacherShiftWaits`). */
  teacherWindows: number;
  brokenDays: number;
  /** Дні груп (окремо чисельник і знаменник) з 4-ю парою зміни (contracts `LatePairs`). */
  latePairs: number;
  maxPairsPerDay: number;
  /** @inferred — склад рядка контракт не описує */
  perGroup: Array<GroupMetricsDto>;
  /*
   * Адитивно (genfix §3.3): у збережених раніше метриках полів немає або вони null — тоді показник не показуємо.
   * Усі лічильники — окремо для чисельника і знаменника, як і решта.
   */
  /** Жорсткі конфлікти версії (VersionAnalysis). */
  hardConflicts?: Nullable<number>;
  /** Порожні пари викладача між парою групи однієї зміни і парою групи іншої зміни. */
  teacherShiftWaits?: Nullable<number>;
  /** Дні груп з однією парою. */
  studentOnePairDays?: Nullable<number>;
  /** Слоти, де одна підгрупа на парі, а інша без заняття. */
  loneSubgroupSlots?: Nullable<number>;
  /** Робочі дні викладачів. */
  teacherDays?: Nullable<number>;
  /** Дні викладачів з однією парою. */
  teacherOnePairDays?: Nullable<number>;
  /** Дні викладачів, коли пар більше за налаштування `teacherMaxPairsPerDay`. */
  teacherOverloadDays?: Nullable<number>;
}

export interface GroupMetricsDto {
  group: GroupRef;
  windows: number;
  brokenDays: number;
  latePairs: number;
  maxPairsPerDay: number;
  /** Адитивно (genfix §3.3), null у старих метриках. */
  onePairDays?: Nullable<number>;
  loneSubgroupSlots?: Nullable<number>;
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
  /** Версія уроку (оптимістичне блокування): правка з іншою версією → 409 `stale-data`. */
  version?: number;
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
  /** PUT: версія уроку, яку бачив диспетчер (застаріла → 409 `stale-data`). */
  version?: number;
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
  /** Версія уроку, яку бачив диспетчер (застаріла → 409 `stale-data`). */
  version?: number;
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
 * яких не створено, бо їх закриває зміна; `conflicts` — HARD-конфлікти лишених змін з новою версією;
 * `pendingDecisions` — SOFT: заняття попередньої версії, які лишились, бо про них є відкрите рішення
 * (після скасування чи відмови їх замінить нова версія).
 */
export interface PublishVersionResponse {
  versionId: Uuid;
  effectiveFrom: IsoDate;
  materializedLessons: number;
  keptLessons: number;
  coveredByChanges: number;
  conflicts: Conflict[];
  pendingDecisions: Conflict[];
}

// ───────────────────────── 2. Admin: генерація ─────────────────────────

/**
 * Ваги мʼяких правил генератора (contracts `GenerationWeights`, 0 — не враховувати, 0..1000).
 * Перші пʼять — з першої версії контракту; решта адитивні (genfix §1.1): у запиті їх можна не надсилати
 * (core підставить типові, `teacherWindows` — як `windows`), у відповіді core віддає всі 14.
 */
export interface GenerationWeights {
  /** Вікна груп. */
  windows: number;
  brokenDays: number;
  /** 4-та пара зміни. */
  latePairs: number;
  preferences: number;
  homeRoom: number;
  /** Вікна викладачів у межах зміни. */
  teacherWindows: number;
  /** Очікування викладача між парою 1-ї зміни і парою 2-ї. */
  teacherShiftWait: number;
  /** Не переставляти пари без потреби (відносно базової версії). */
  stability: number;
  subgroupsTogether: number;
  studentOnePairDay: number;
  teacherDays: number;
  teacherOverload: number;
  disciplineTwice: number;
  /**
   * Пара предмета, який у базовій версії стояв лише в спортзалі чи на спортмайданчику, в іншій аудиторії
   * (адитивно, genfix repair; замінює колишній жорсткий тип аудиторії, виведений з бази).
   */
  specialRooms: number;
}

/** Ваги так, як їх може віддати старий сервер чи стара задача: лише перші пʼять. */
type FirstWeightKey = 'windows' | 'brokenDays' | 'latePairs' | 'preferences' | 'homeRoom';
export type GenerationWeightsWire = Pick<GenerationWeights, FirstWeightKey> & {
  [K in Exclude<keyof GenerationWeights, FirstWeightKey>]?: Nullable<GenerationWeights[K]>;
};

/** POST /api/admin/generation/jobs → 202 {jobId} */
export interface GenerationJobRequest {
  semesterId: Uuid;
  scope: { groupIds?: Uuid[]; courses?: number[] };
  baseVersionId?: Nullable<Uuid>;
  /**
   * Старий фіксований ліміт, 10..600 с. Без нього — автоматичний режим: генерація зупиняється сама, коли розклад
   * перестає покращуватися. Вебзастосунок його більше не надсилає; поле лишається для скриптів.
   */
  timeLimitSeconds?: Nullable<number>;
  /** Можна надіслати лише частину нових ваг: решту core підставить типовими. */
  weights?: Nullable<GenerationWeightsWire>;
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
  /** Адитивно (genfix §1.3): стабільний латинський id обмеження. */
  constraintId?: Nullable<string>;
  /** Рядок-винагорода (половинки в парі): це досягнення, а не порушення. null — false. */
  bonus?: Nullable<boolean>;
  /** Скільки з `matchCount` зачіпає пари, які генератор міг рухати. null — невідомо (стара задача). */
  scopeMatchCount?: Nullable<number>;
}

/** Що змінилось відносно базової версії (genfix §3.2): лише для SOLVED. */
export interface GenerationOutcomeDto {
  /** Пари з базовим місцем, які стали в інший день, пару чи тиждень. */
  moved: number;
  /** Пари на тому самому місці, але в іншій аудиторії. */
  roomChanged: number;
  /** Пари, що мали базове місце. */
  total: number;
  perGroup: Array<{ group: GroupRef; moved: number; total: number }>;
  /** Адитивно: пари на тому самому місці, яким дали аудиторію, коли в базовій її не було. null — стара задача. */
  roomAssigned?: Nullable<number>;
  /** Адитивно: пари з навантаження, яких у базовій не було (нові). null — стара задача. */
  added?: Nullable<number>;
  /** Адитивно: пари, які розвʼязувач не рухав (закріплені, потоки, поза вибором). null — стара задача. */
  kept?: Nullable<number>;
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
  /** Старий фіксований ліміт запуску, 10..600 с; null — автоматичний режим (зупиняється сама). */
  timeLimitSeconds: Nullable<number>;
  analysis: Nullable<ConstraintAnalysisDto[]>;
  metricsBefore: Nullable<MetricsDto>;
  metricsAfter: Nullable<MetricsDto>;
  error: Nullable<string>;
  requestedBy: Nullable<string>;
  /*
   * Адитивно (genfix §3.2): власні параметри задачі. Старий сервер їх не надсилає — тоді інтерфейс
   * не вигадує їх із поточної форми, а пише «невідомо».
   */
  /** Як просили: `{[], []}` — усі групи. */
  scope?: Nullable<{ groupIds: Uuid[]; courses: number[] }>;
  /** Групи, які генератор міг рухати, за курсом і кодом. */
  groups?: Nullable<GroupRef[]>;
  weights?: Nullable<GenerationWeightsWire>;
  /** З `bestScore`; null — ще немає повного розкладу. */
  hardScore?: Nullable<number>;
  softScore?: Nullable<number>;
  outcome?: Nullable<GenerationOutcomeDto>;
  /**
   * Адитивно (genfix repair): true — задача зберегла лише пʼять ваг першої схеми, тож інших правил для неї не
   * було, а `weights` показує їхні типові значення лише для форми. null — сервер старий або ваг немає.
   */
  legacyWeights?: Nullable<boolean>;
  /** Адитивно: диспетчер зупинив задачу, що розвʼязується; найкращий варіант на шляху (задача ще активна). */
  stopRequested?: Nullable<boolean>;
  /** Адитивно: чому задача з розкладом зупинилась; null — старі задачі й задачі без розкладу. */
  stopReason?: Nullable<GenerationStopReason>;
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
  /** Імпортоване паперове розпорядження: без файлів DOCX/PDF/PNG. */
  imported: boolean;
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
  /** Імпортоване паперове розпорядження (лише текст): файлів DOCX/PDF/PNG немає. */
  imported: boolean;
}

/** GET /api/admin/orders/numbering?semesterId — наступний номер = max(останній + 1, перший). */
export interface OrderNumberingDto {
  semesterId: Uuid;
  firstNumber: number;
  lastNumber: Nullable<number>;
  nextNumber: number;
}

export interface UpdateOrderNotesRequest {
  notes: string[];
  /** `version` чернетки, яку редагував диспетчер: застаріла → 409 `stale-data` (примітки друкуються в розпорядженні). */
  version: number;
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
  /** Сума годин на тиждень записів навантаження дисципліни. */
  hoursPerWeek: number;
  /** Звідки план: AUTO — рахує система, FIXED — задане число, MIXED — записи різні. */
  planSource: PlanSource | 'MIXED';
}

/**
 * GET /api/admin/accounting/theory: скільки тижнів група на теорії в семестрі системи (рішення власника
 * 15.11.2026: тижні НС, практики, сесії, зборів і канікул не рахуються й не надолужуються) і що її знімає.
 */
export interface GroupTheoryDto {
  group: GroupRef;
  /** Тижні семестру (від понеділка). */
  semesterWeeks: number;
  /** Тижні, у яких група має хоч один навчальний день. */
  theoryWeeks: number;
  numeratorWeeks: number;
  denominatorWeeks: number;
  studyDays: number;
  /** Тижні теорії з графіка освітнього процесу — лише для звірки; null, якщо графік не імпортовано. */
  graphTheoryWeeks: Nullable<number>;
  offPeriods: GroupOffPeriodDto[];
}

/** Період, що знімає групу з пар: код виду — як у графіку (НС, НП, К…). */
export interface GroupOffPeriodDto {
  id: Nullable<Uuid>;
  kind: { code: string; name: string; color: Nullable<string>; effect: PeriodEffect };
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
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
  /**
   * Скільки днів назад гості бачать заміни, розпорядження й розклад (0..365, типово 7; 0 — лише від сьогодні).
   * PUT без поля (старий клієнт) лишає збережене значення.
   */
  changesHistoryDays: number;
  /**
   * Найбільше пар на день у викладача для генератора (1..8, типово 4; genfix §3.4). Старий сервер не надсилає —
   * тоді поля в формі немає; PUT без поля лишає збережене значення.
   */
  teacherMaxPairsPerDay?: number;
  activeBellProfileId: Nullable<Uuid>;
  /** Версія налаштувань з GET; PUT зі старішою — 409 `stale-data` (хтось зберіг раніше). */
  version: number;
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
  /** Усі види періодів, і вимкнені теж (адитивно; старіший core поля не має). */
  periodKinds?: Array<Pick<PeriodKindDto, 'id' | 'code' | 'name' | 'effect' | 'color' | 'aliases' | 'noteTemplate' | 'active'>>;
}

// ───────────────────────── 5. Importer ─────────────────────────

export type ImportKind = 'TIMETABLE_XLSX' | 'DISCIPLINE_PROGRAM' | 'CURRICULUM' | 'ORDER_DOCUMENT' | 'ACADEMIC_CALENDAR_XLSX';
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
  /**
   * Чи можна застосувати зараз: READY, FAILED з чернеткою або NEEDS_REVIEW після відмови core (`error`) —
   * без блокувальних рядків.
   */
  canApply: boolean;
  /** Версія завдання: її повертають, зберігаючи виправлені рядки розпорядження (PUT …/draft) чи параметри (PUT …/options). */
  version?: number;
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
  /**
   * Ранковий розклад о 7:30 (лише GROUP_CHAT і SUBSCRIBER). Новий чат групи — `false` (вмикає адміністратор
   * чату командою /rozklad_on або диспетчер тут), підписник — `true`. Змінює `PATCH /notify/bindings/{id}`.
   */
  digestEnabled: boolean;
  createdAt: IsoInstant;
  /**
   * Telegram-акаунт, що привʼязав чат викладача: «Імʼя (@nick)». `null` для інших чатів і для чатів викладачів,
   * привʼязаних раніше, ніж notification почав його записувати.
   */
  telegramAccount: Nullable<string>;
}

/** PATCH /api/notify/bindings/{id} (адитивно, раунд 7). 422 `digest-not-supported` для викладача й диспетчерської. */
export interface BindingUpdateRequest {
  digestEnabled: boolean;
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
  | 'SUBSTITUTION_NOTICE'
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

// ── Листи на пошту (CONTRACTS §6, «E-mail notifications», 15.11.2026) ──

/** Кому призначений вид листа: викладачу про його заміни чи диспетчеру. */
export type EmailAudience = 'TEACHER' | 'ADMIN';

/**
 * Вид листа (`EmailKind` у notification). Типові значення й те, чи можна вимкнути, приходять із сервера
 * (`defaultEnabled`, `switchable`); новий вид, якого SPA ще не знає, показується з загальним підписом.
 */
export type EmailKind =
  | 'SUBSTITUTION_NOTICE'
  | 'SUBSTITUTION_REQUEST'
  | 'SUBSTITUTION_CHANGED'
  | 'SUBSTITUTION_CANCELLED'
  | 'SUBSTITUTION_REMINDER'
  | 'ORDER_TEACHER'
  | 'SECURITY_TELEGRAM_RELINKED'
  | 'GENERATION_FINISHED'
  | 'SUBSTITUTION_DECLINED'
  | 'SUBSTITUTION_OBJECTION'
  | 'REQUEST_OVERDUE'
  | 'ABSENCE_REPORTED'
  | 'TEACHER_UNREACHABLE';

/** VERIFIED — листи йдуть; UNVERIFIED — адресу ніхто не підтвердив; MISSING — на обліковому записі немає e-mail. */
export type EmailAddressStatus = 'VERIFIED' | 'UNVERIFIED' | 'MISSING';
/** Чим підтверджено адресу: входом через Google або корпоративним доменом коледжу. */
export type EmailVerifiedBy = 'GOOGLE' | 'TRUSTED_DOMAIN';

export interface EmailKindSettingDto {
  kind: EmailKind;
  audience: EmailAudience;
  /** Вибір людини для цього виду; зберігається й тоді, коли загальний перемикач вимкнено. */
  enabled: boolean;
  defaultEnabled: boolean;
  /** `false` — лист приходить завжди (лист безпеки): замість перемикача — «Завжди». */
  switchable: boolean;
  /** Назва й опис виду від сервера: SPA показує власні тексти, а ці — для виду, якого ще не знає. */
  label?: Nullable<string>;
  description?: Nullable<string>;
}

/**
 * GET/PUT /api/notify/email/me (TEACHER, ADMIN): мої налаштування листів. Адреса — власна, без маски,
 * лише для читання (її бере сервер з облікового запису). Види — лише ті, що стосуються мене:
 * викладацькі, якщо обліковий запис повʼязаний з викладачем, і диспетчерські для ролі ADMIN.
 */
export interface EmailPreferencesDto {
  /** Чи надсилає сервер листи взагалі (налаштовано SMTP); `reason` — чому ні. */
  channel: { enabled: boolean; reason?: Nullable<string> };
  address?: Nullable<string>;
  addressStatus: EmailAddressStatus;
  verifiedBy?: Nullable<EmailVerifiedBy>;
  /** Загальний перемикач «Надсилати листи». */
  enabled: boolean;
  kinds: EmailKindSettingDto[];
  /** Версія для оптимістичного блокування (необовʼязкова: сервер може її не віддавати). */
  version?: Nullable<number>;
}

/**
 * PUT /api/notify/email/me. `kinds` — лише ті види, що змінюються (незгадані лишаються як були); SPA шле стан
 * усіх перемикачів, а сервер зберігає лише відмінності від типових. 400 `errors[kinds.X]` — невідомий вид;
 * 422 `email-kind-not-allowed`; 409 — застаріла `version`.
 */
export interface EmailPreferencesRequest {
  enabled: boolean;
  kinds: Partial<Record<EmailKind, boolean>>;
  version?: Nullable<number>;
}

/**
 * POST /api/notify/email/me/test → 202. 422 `email-account-inactive`/`email-missing`/`email-not-verified`,
 * 503 `email-disabled`, 429 `email-test-limit`.
 */
export interface EmailTestResultDto {
  status: string;
}

/** SMTP — справжня пошта; DEV_CATCHER — Mailpit на стенді (листи нікуди не йдуть); OFF — не налаштовано. */
export type EmailMode = 'SMTP' | 'DEV_CATCHER' | 'OFF';

/** Шифрування зʼєднання з SMTP: STARTTLS (порт 587), SSL (465) або NONE (Mailpit на стенді). */
export type EmailTls = 'STARTTLS' | 'SSL' | 'NONE';

/**
 * GET /api/notify/email/status (ADMIN). Ніколи не містить логіна й пароля SMTP. `host`, `port`, `tls` —
 * `null`, коли пошту не налаштовано; `problem` — чому листи не йдуть або попередження.
 */
export interface EmailStatusDto {
  enabled: boolean;
  mode: EmailMode;
  host: Nullable<string>;
  port: Nullable<number>;
  from: Nullable<string>;
  replyTo: Nullable<string>;
  tls: Nullable<EmailTls>;
  problem?: Nullable<string>;
}

/** SUPERSEDED — замість цього листа пішов новіший про ту саму заміну. */
export type EmailDeliveryStatus = 'QUEUED' | 'RETRYING' | 'SENT' | 'FAILED' | 'SUPERSEDED';

/** Запис журналу GET /api/notify/email/deliveries (ADMIN; сторінка `Page<EmailDeliveryDto>`, нові спершу). */
export interface EmailDeliveryDto {
  id: Uuid;
  /** Вид листа; `TEST` — тестовий лист собі. */
  kind: EmailKind | 'TEST';
  /** Маскована адреса: `k•••@vtc.vn.ua`. */
  to: string;
  accountName: Nullable<string>;
  subject: string;
  status: EmailDeliveryStatus;
  attempts: number;
  error: Nullable<string>;
  createdAt: IsoInstant;
  sentAt: Nullable<IsoInstant>;
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
