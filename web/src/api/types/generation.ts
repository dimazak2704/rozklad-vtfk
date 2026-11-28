import type { GenerationJobStatus, GenerationStopReason, GroupRef, IsoInstant, Nullable, Uuid } from './common';
import type { MetricsDto } from './timetable';

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
  /**
   * Адитивно: оцінка розвʼязувачем розкладу, з якого задача стартувала (база), за її ж правилами й вагами, до
   * розвʼязання. Це «Було» жорстких порушень: перевірка конфліктів редактора не знає ліміту пар у пʼятницю й
   * вважає вікна груп мʼякими. null — генерація з нуля, старі задачі й старий планувальник.
   */
  baseHardScore?: Nullable<number>;
  baseSoftScore?: Nullable<number>;
}
