import type { DisciplineRef, EntityType, GroupRef, IsoDate, IsoInstant, IsoTime, LessonViewKind, Nullable, OrderRowType, Parity, PeriodEffect, RoomRef, RoomType, ShiftNumber, TeacherRef, Uuid, WeekParity, Weekday } from './common';

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
