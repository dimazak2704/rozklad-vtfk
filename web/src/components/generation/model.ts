/** Генератор: параметри форми, перетворення в запит, тривалості, рядки «Було / Стало», параметри задачі. */
import {
  DEFAULT_WEIGHTS,
  jobHardScore,
  normalizeWeights,
  parseScore,
  solvedWithConflicts,
  TIME_LIMIT_MAX,
  TIME_LIMIT_MIN,
} from '@/api/generation';
import type {
  ConstraintAnalysisDto,
  GenerationJobDto,
  GenerationJobRequest,
  GenerationOutcomeDto,
  GenerationWeights,
  GroupRef,
  MetricsDto,
  TimetableVersionDto,
  Uuid,
} from '@/api/types';
import { countLabel, plural } from '@/lib/labels';
import { withFixedRules } from './priorities';

export type ScopeMode = 'all' | 'courses' | 'groups';

export interface GenerationFormState {
  scope: ScopeMode;
  courses: number[];
  groupIds: Uuid[];
  /** null — без базової версії (одиниці з навантаження). */
  baseVersionId: Uuid | null;
  timeLimitSeconds: number;
  weights: GenerationWeights;
}

export const DEFAULT_TIME_LIMIT = 60;

export function initialForm(baseVersionId: Uuid | null): GenerationFormState {
  return {
    scope: 'all',
    courses: [],
    groupIds: [],
    baseVersionId,
    timeLimitSeconds: DEFAULT_TIME_LIMIT,
    weights: { ...DEFAULT_WEIGHTS },
  };
}

/** Помилки форми до відправлення (сервер перевірить ще раз). Порожньо — можна запускати. */
export function validateForm(form: GenerationFormState): Partial<Record<'scope' | 'timeLimitSeconds', string>> {
  const errors: Partial<Record<'scope' | 'timeLimitSeconds', string>> = {};
  if (form.scope === 'courses' && form.courses.length === 0) errors.scope = 'Оберіть хоча б один курс';
  if (form.scope === 'groups' && form.groupIds.length === 0) errors.scope = 'Додайте хоча б одну групу';
  if (!Number.isInteger(form.timeLimitSeconds) || form.timeLimitSeconds < TIME_LIMIT_MIN || form.timeLimitSeconds > TIME_LIMIT_MAX) {
    errors.timeLimitSeconds = `Ліміт — від ${TIME_LIMIT_MIN} до ${TIME_LIMIT_MAX} секунд`;
  }
  return errors;
}

/**
 * Запит несе всі 14 ваг (і ті, що не показані як пріоритети, — з типовими значеннями): core прийняв би й
 * частину, але так задача точно та, яку бачила людина.
 */
export function toJobRequest(form: GenerationFormState, semesterId: Uuid): GenerationJobRequest {
  return {
    semesterId,
    scope: {
      ...(form.scope === 'courses' ? { courses: form.courses } : {}),
      ...(form.scope === 'groups' ? { groupIds: form.groupIds } : {}),
    },
    baseVersionId: form.baseVersionId,
    timeLimitSeconds: form.timeLimitSeconds,
    weights: form.weights,
  };
}

/** «1 хв 05 с», «45 с». */
export function formatSeconds(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds));
  if (seconds < 60) return `${seconds}\u00a0с`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest === 0 ? `${minutes}\u00a0хв` : `${minutes}\u00a0хв ${String(rest).padStart(2, '0')}\u00a0с`;
}

/** Скільки секунд задача розвʼязувалась (за останньою точкою прогресу або моментами старту й кінця). */
export function elapsedSeconds(job: GenerationJobDto): number {
  if (job.startedAt && job.finishedAt) return (Date.parse(job.finishedAt) - Date.parse(job.startedAt)) / 1000;
  return job.progress.at(-1)?.t ?? 0;
}

// ───────────────────────── «Було / Стало» ─────────────────────────

export interface ComparisonRow {
  key: string;
  label: string;
  /** null — «Було» невідоме (сервер не порахував метрики бази): показуємо лише «Стало». */
  before: number | null;
  after: number;
}

type CountKey = Exclude<keyof MetricsDto, 'perGroup' | 'hardConflicts'>;

/** Показники «Було / Стало» у порядку таблиці; нові (genfix §3.3) — лише коли сервер їх порахував. */
const METRIC_ROWS: ReadonlyArray<{ key: CountKey; label: string }> = [
  { key: 'groupWindows', label: 'Вікна груп' },
  { key: 'teacherWindows', label: 'Вікна викладачів' },
  { key: 'teacherShiftWaits', label: 'Очікування між змінами' },
  { key: 'brokenDays', label: 'Рвані дні' },
  { key: 'latePairs', label: '4-та пара зміни' },
  { key: 'studentOnePairDays', label: 'Дні груп з однією парою' },
  { key: 'loneSubgroupSlots', label: 'Підгрупа на парі, інша без заняття' },
  { key: 'teacherDays', label: 'Робочі дні викладачів' },
  { key: 'teacherOnePairDays', label: 'Дні викладачів з однією парою' },
  { key: 'teacherOverloadDays', label: 'Перевантажені дні викладачів' },
  { key: 'maxPairsPerDay', label: 'Макс. пар на день' },
];

const BASE_KEYS = ['groupWindows', 'teacherWindows', 'brokenDays', 'latePairs', 'maxPairsPerDay'] as const;

/**
 * Метрики, яким можна вірити як «Було». Нульовий набір — це не «ідеальний розклад», а метрики, яких
 * не порахували (у версії з парами «Макс. пар на день» не буває 0), тому такий набір відкидаємо.
 */
export function usableMetrics(metrics: MetricsDto | null | undefined): MetricsDto | null {
  if (!metrics) return null;
  return BASE_KEYS.every((key) => metrics[key] === 0) ? null : metrics;
}

const numberOrNull = (value: number | null | undefined): number | null => (typeof value === 'number' ? value : null);

/** Базова версія задачі, як її знає список версій. */
export type BaseVersionInfo = Pick<TimetableVersionDto, 'metrics' | 'hardConflicts'>;

/** «Було» метрик: з задачі, а якщо там порожньо — з базової версії. */
function metricsBefore(job: GenerationJobDto, base: BaseVersionInfo | null | undefined): MetricsDto | null {
  return usableMetrics(job.metricsBefore) ?? (job.baseVersionId ? usableMetrics(base?.metrics) : null);
}

/**
 * «Було» жорстких конфліктів — з бази, а не з першої точки прогресу (genfix B2): розвʼязувач стартує
 * зі своїх тимчасових накладок, і «34 → 0» для бази без конфліктів було б неправдою. Порядок:
 * `metricsBefore.hardConflicts`, далі конфлікти базової версії; нічого — «Було» не показуємо.
 */
export function hardBefore(job: GenerationJobDto, base?: BaseVersionInfo | null): number | null {
  const fromJob = numberOrNull(job.metricsBefore?.hardConflicts);
  if (fromJob !== null) return fromJob;
  return job.baseVersionId && base ? base.hardConflicts : null;
}

/** «Стало» жорстких: з метрик результату, інакше з оцінки задачі. */
export function hardAfter(job: GenerationJobDto): number | null {
  const fromMetrics = numberOrNull(job.metricsAfter?.hardConflicts);
  if (fromMetrics !== null) return fromMetrics;
  const hard = jobHardScore(job);
  return hard === null ? null : Math.abs(hard);
}

/**
 * Рядки «Було / Стало»: жорсткі конфлікти і метрики версії. Якщо «Було» невідоме, його не показуємо
 * зовсім, щоб «0 → 18» не виглядало погіршенням.
 */
export function comparisonRows(job: GenerationJobDto, base?: BaseVersionInfo | null): ComparisonRow[] {
  const after = job.metricsAfter;
  if (!after) return [];
  const before = metricsBefore(job, base);
  const rows: ComparisonRow[] = [];
  const hard = hardAfter(job);
  if (hard !== null) rows.push({ key: 'hard', label: 'Жорсткі конфлікти', before: hardBefore(job, base), after: hard });
  for (const { key, label } of METRIC_ROWS) {
    const value = numberOrNull(after[key]);
    if (value === null) continue;
    rows.push({ key, label, before: before ? numberOrNull(before[key]) : null, after: value });
  }
  return rows;
}

// ───────────────────────── Аналіз обмежень (genfix B7) ─────────────────────────

export interface AnalysisRow {
  key: string;
  constraint: string;
  count: number;
  hard: boolean;
}

export interface AnalysisSplit {
  /** Порушення, що зачіпають пари, які генератор міг рухати. */
  remaining: AnalysisRow[];
  /** Бонуси (половинки в парі): це досягнення, а не порушення. */
  achieved: AnalysisRow[];
  /** Обмеження без порушень у вибраних групах. */
  clean: string[];
  /** Порушення лише в закріплених парах поза вибором. */
  outsideScope: number;
}

/** Рядок-бонус: за прапорцем, а в старих задачах — за додатним мʼяким балом (лише бонус його має). */
export function isBonus(item: ConstraintAnalysisDto): boolean {
  if (typeof item.bonus === 'boolean') return item.bonus;
  const score = parseScore(item.score);
  return score !== null && score.soft > 0;
}

export function splitAnalysis(items: readonly ConstraintAnalysisDto[]): AnalysisSplit {
  const split: AnalysisSplit = { remaining: [], achieved: [], clean: [], outsideScope: 0 };
  for (const item of items) {
    const key = item.constraintId ?? item.constraint;
    if (isBonus(item)) {
      if (item.matchCount > 0) split.achieved.push({ key, constraint: item.constraint, count: item.matchCount, hard: false });
      continue;
    }
    const hard = (parseScore(item.score)?.hard ?? 0) < 0;
    const inScope = Math.min(item.matchCount, numberOrNull(item.scopeMatchCount) ?? item.matchCount);
    split.outsideScope += item.matchCount - inScope;
    if (inScope > 0) split.remaining.push({ key, constraint: item.constraint, count: inScope, hard });
    else split.clean.push(item.constraint);
  }
  return split;
}

// ───────────────────────── Що змінилось і по групах (genfix B8) ─────────────────────────

const PAIR_FORMS_GEN = ['пари', 'пар', 'пар'] as const;

/** «Переставлено 34 з 53 пар, ще 5 — в іншій аудиторії.» null — сервер не порахував або бази не було. */
export function outcomeSentence(outcome: GenerationOutcomeDto | null | undefined): string | null {
  if (!outcome || outcome.total === 0) return null;
  const rooms = outcome.roomChanged > 0 ? `, ще ${outcome.roomChanged} — в іншій аудиторії` : '';
  return `Переставлено ${outcome.moved} з ${outcome.total}\u00a0${plural(outcome.total, PAIR_FORMS_GEN)}${rooms}.`;
}

export type GroupColumnKey = 'latePairs' | 'windows' | 'brokenDays' | 'onePairDays';

export const GROUP_COLUMNS: ReadonlyArray<{ key: GroupColumnKey; label: string }> = [
  { key: 'latePairs', label: '4-та пара зміни' },
  { key: 'windows', label: 'Вікна' },
  { key: 'brokenDays', label: 'Рвані дні' },
  { key: 'onePairDays', label: 'Дні з однією парою' },
];

export interface GroupComparisonRow {
  group: GroupRef;
  cells: Array<{ key: GroupColumnKey; before: number | null; after: number | null }>;
  /** Скільки пар групи переставлено; null — невідомо. */
  moved: { moved: number; total: number } | null;
}

/**
 * Таблиця по групах області: показники до і після та скільки пар переставлено. Групи — з самої задачі
 * (`groups`), а для старих задач без списку — ті, що є в метриках результату.
 */
export function groupComparison(job: GenerationJobDto, base?: BaseVersionInfo | null): GroupComparisonRow[] {
  const after = job.metricsAfter?.perGroup ?? [];
  if (after.length === 0) return [];
  const beforeById = new Map((metricsBefore(job, base)?.perGroup ?? []).map((row) => [row.group.id, row]));
  const afterById = new Map(after.map((row) => [row.group.id, row]));
  const movedById = new Map((job.outcome?.perGroup ?? []).map((row) => [row.group.id, row]));
  const groups = job.groups && job.groups.length > 0 ? job.groups : after.map((row) => row.group);
  return groups.map((group) => {
    const a = afterById.get(group.id);
    const b = beforeById.get(group.id);
    const moved = movedById.get(group.id);
    return {
      group,
      cells: GROUP_COLUMNS.map(({ key }) => ({
        key,
        before: b ? numberOrNull(b[key]) : null,
        after: a ? numberOrNull(a[key]) : null,
      })),
      moved: moved ? { moved: moved.moved, total: moved.total } : null,
    };
  });
}

// ───────────────────────── Параметри задачі (genfix B8) ─────────────────────────

const GROUP_FORMS = ['група', 'групи', 'груп'] as const;
const SHOWN_CODES = 6;

/** Які групи генерувались: «Усі групи», «4 курс · 12 груп», «4КІ1, 4КІ2, 4КІ3». null — стара задача без області. */
export function jobScopeText(job: Pick<GenerationJobDto, 'scope' | 'groups'>): string | null {
  const scope = job.scope;
  if (!scope) return null;
  if (scope.groupIds.length === 0 && scope.courses.length === 0) return 'Усі групи';
  const groups = job.groups ?? [];
  const parts: string[] = [];
  if (scope.courses.length > 0) {
    const courses = [...scope.courses].sort((a, b) => a - b);
    parts.push(courses.length === 1 ? `${courses[0] ?? ''} курс` : `${courses.join(', ')} курси`);
  }
  if (scope.groupIds.length > 0) {
    const byId = new Map(groups.map((group) => [group.id, group.code]));
    const codes = scope.groupIds.map((id) => byId.get(id) ?? '—');
    const shown = codes.slice(0, SHOWN_CODES).join(', ');
    parts.push(codes.length > SHOWN_CODES ? `${shown} і ще ${codes.length - SHOWN_CODES}` : shown);
  }
  const text = parts.join(' і ');
  return scope.courses.length > 0 && groups.length > 0 ? `${text} · ${countLabel(groups.length, GROUP_FORMS)}` : text;
}

/**
 * Пріоритети задачі одним рядком для історії й панелі. Стара задача (`legacyWeights`) мала лише пʼять правил
 * першої версії генератора: показуємо їхні ваги, а не «типові» правила, яких тоді не було.
 */
export function jobPrioritiesText(job: GenerationJobDto, changed: ReadonlyArray<{ rule: { label: string }; text: string }>): string {
  const weights = job.weights;
  if (job.legacyWeights && weights) {
    return (
      `стара версія генератора, лише 5 правил: вікна ${weights.windows}, рвані дні ${weights.brokenDays}, ` +
      `пізні пари ${weights.latePairs}, побажання ${weights.preferences}, аудиторія ${weights.homeRoom}`
    );
  }
  return changed.length === 0 ? 'типові' : changed.map(({ rule, text }) => `${rule.label} — ${text.toLocaleLowerCase('uk')}`).join('; ');
}

/** Чи має задача власні параметри (новий сервер). Без них параметри не вигадуємо з поточної форми. */
export function jobHasParams(job: GenerationJobDto): boolean {
  return Boolean(job.scope && job.weights);
}

/** Форма з параметрів задачі: у лівій панелі, поки задача йде, і для «Взяти ці параметри». */
export function formFromJob(job: GenerationJobDto): GenerationFormState {
  const scope = job.scope;
  const mode: ScopeMode = scope && scope.groupIds.length > 0 ? 'groups' : scope && scope.courses.length > 0 ? 'courses' : 'all';
  return {
    scope: mode,
    courses: mode === 'courses' && scope ? [...scope.courses] : [],
    groupIds: mode === 'groups' && scope ? [...scope.groupIds] : [],
    baseVersionId: job.baseVersionId,
    timeLimitSeconds: job.timeLimitSeconds ?? DEFAULT_TIME_LIMIT,
    // правила «Діє завжди» не вибирають: стара задача могла нести для них іншу вагу, у формі — типова
    weights: withFixedRules(normalizeWeights(job.weights) ?? { ...DEFAULT_WEIGHTS }),
  };
}

const STATUS_TITLE: Record<GenerationJobDto['status'], string> = {
  QUEUED: 'У черзі',
  RUNNING: 'Розвʼязується',
  SOLVED: 'Готово',
  FAILED: 'Не вдалося',
  CANCELLED: 'Скасовано',
};

/** Стан задачі словом; SOLVED з hard < 0 — «Готово з конфліктами» (genfix B6), а не зелене «Готово». */
export function jobStatusTitle(job: GenerationJobDto): string {
  return solvedWithConflicts(job) ? 'Готово з конфліктами' : STATUS_TITLE[job.status];
}

export interface CompletionNotice {
  level: 'success' | 'warning';
  title: string;
  description: string;
}

/**
 * Сповіщення про завершену задачу (SSE `generation.completed`). Готово без конфліктів — успіх; готово з
 * жорсткими конфліктами — попередження з їх кількістю, а не зелена галочка поруч із «Готово з конфліктами»
 * (genfix B6). Інші стани сповіщення не мають: скасування й помилку видно на панелі задачі.
 */
export function completionNotice(job: GenerationJobDto): CompletionNotice | null {
  if (job.status !== 'SOLVED') return null;
  if (solvedWithConflicts(job)) {
    const hard = Math.abs(jobHardScore(job) ?? 0);
    return {
      level: 'warning',
      title: 'Генерацію завершено з конфліктами',
      description: `Чернетку збережено, але в ній ${countLabel(hard, ['жорсткий конфлікт', 'жорсткі конфлікти', 'жорстких конфліктів'])}: виправте їх перед публікацією.`,
    };
  }
  return {
    level: 'success',
    title: 'Генерацію завершено',
    description: 'Результат збережено як нову чернетку — відкрийте й перегляньте її.',
  };
}

/** Рівні поділки осі часу без повторів після округлення: 0, 5, 10, 15 с. */
export function timeTicks(end: number, target = 5): number[] {
  const safeEnd = Math.max(1, Math.ceil(end));
  const raw = safeEnd / target;
  const step = [1, 2, 5, 10, 15, 30, 60, 120, 300].find((candidate) => candidate >= raw) ?? Math.ceil(raw / 60) * 60;
  const ticks: number[] = [];
  for (let t = 0; t <= safeEnd; t += step) ticks.push(t);
  return ticks;
}

const BASELINE_FORMS = {
  hard: ['жорсткий конфлікт', 'жорсткі конфлікти', 'жорстких конфліктів'],
} as const;

/**
 * «Було» до запуску одним реченням: що генератор намагатиметься покращити. Нулі не називаємо;
 * плитки з числами не потрібні — після запуску з'явиться таблиця «Було і стало».
 */
export function baselineSentence(version: TimetableVersionDto): string {
  // одне число, що вирішує: конфлікти; вікна й рвані дні — у «Подробицях» редактора розкладу
  if (version.hardConflicts > 0) return `${countLabel(version.hardConflicts, BASELINE_FORMS.hard)}.`;
  const m = version.metrics;
  // «4-та пара зміни» — не вада (коледж вважає її звичайною), тож до «лишились вікна» не входить
  const soft = m ? m.teacherWindows + m.groupWindows + m.brokenDays : 0;
  return soft > 0 ? 'Конфліктів немає, лишились вікна чи рвані дні.' : 'Конфліктів і вікон немає.';
}
