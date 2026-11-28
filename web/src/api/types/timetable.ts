import type { Conflict, DisciplineRef, GroupRef, IsoDate, IsoInstant, LessonKind, Nullable, Parity, RoomRef, TeacherRef, TimetableVersionSource, TimetableVersionStatus, Uuid, Weekday } from './common';

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
