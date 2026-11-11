/** Генератор: параметри форми, перетворення в запит, тривалості, рядки «Було / Стало». */
import { DEFAULT_WEIGHTS, TIME_LIMIT_MAX, TIME_LIMIT_MIN } from '@/api/generation';
import type { GenerationJobDto, GenerationJobRequest, GenerationWeights, MetricsDto, TimetableVersionDto, Uuid } from '@/api/types';
import { countLabel } from '@/lib/labels';

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

export interface ComparisonRow {
  key: string;
  label: string;
  /** null — «Було» невідоме (сервер не порахував метрики бази): показуємо лише «Стало». */
  before: number | null;
  after: number;
}

type MetricKey = keyof Omit<MetricsDto, 'perGroup'>;
const METRIC_KEYS: readonly MetricKey[] = ['groupWindows', 'teacherWindows', 'brokenDays', 'latePairs', 'maxPairsPerDay'];

/**
 * Метрики, яким можна вірити як «Було». Нульовий набір — це не «ідеальний розклад», а метрики, яких
 * не порахували (у версії з парами «Макс. пар на день» не буває 0), тому такий набір відкидаємо.
 */
export function usableMetrics(metrics: MetricsDto | null | undefined): MetricsDto | null {
  if (!metrics) return null;
  return METRIC_KEYS.every((key) => metrics[key] === 0) ? null : metrics;
}

/**
 * Рядки «Було / Стало»: жорсткі конфлікти (з оцінки) і метрики версії. «Було» — з задачі, а якщо там
 * порожньо — з базової версії (`fallbackBefore`); якщо й там нічого, «Було» не показуємо зовсім,
 * щоб «0 → 18» не виглядало погіршенням.
 */
export function comparisonRows(job: GenerationJobDto, fallbackBefore?: MetricsDto | null): ComparisonRow[] {
  const after = job.metricsAfter;
  if (!after) return [];
  const before = usableMetrics(job.metricsBefore) ?? usableMetrics(fallbackBefore);
  const first = job.progress[0];
  const last = job.progress.at(-1);
  const rows: ComparisonRow[] = [];
  if (first && last)
    rows.push({
      key: 'hard',
      label: 'Жорсткі конфлікти',
      before: before ? Math.abs(first.hard) : null,
      after: Math.abs(last.hard),
    });
  const metric = (key: MetricKey, label: string) =>
    rows.push({
      key,
      label,
      before: before ? before[key] : null,
      after: after[key],
    });
  metric('groupWindows', 'Вікна груп');
  metric('teacherWindows', 'Вікна викладачів');
  metric('brokenDays', 'Рвані дні');
  metric('latePairs', 'Пізні пари');
  metric('maxPairsPerDay', 'Макс. пар на день');
  return rows;
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

/** Пояснення ваг — в одному розкритті «Що означають ваги?», а не абзацом під кожним повзунком. */
export const WEIGHT_FIELDS: ReadonlyArray<{
  key: keyof GenerationWeights;
  label: string;
  hint: string;
}> = [
  {
    key: 'windows',
    label: 'Вікна',
    hint: 'Штраф за кожну порожню пару між заняттями групи чи викладача в один день.',
  },
  {
    key: 'brokenDays',
    label: 'Рвані дні',
    hint: 'Штраф за день, коли в чисельник і знаменник різна кількість пар або різна перша пара.',
  },
  {
    key: 'latePairs',
    label: 'Пізні пари',
    hint: 'Штраф за заняття після 16:00 (7-ма і 8-ма пари).',
  },
  {
    key: 'preferences',
    label: 'Побажання викладачів',
    hint: 'Штраф за пару в слоті, який викладач позначив як небажаний.',
  },
  {
    key: 'homeRoom',
    label: 'Аудиторія викладача',
    hint: 'Штраф, коли пара не в аудиторії, де викладач зазвичай веде цю дисципліну.',
  },
];

export const WEIGHT_MAX = 30;

const BASELINE_FORMS = {
  hard: ['жорсткий конфлікт', 'жорсткі конфлікти', 'жорстких конфліктів'],
  teacherWindows: ['вікно викладачів', 'вікна викладачів', 'вікон викладачів'],
  groupWindows: ['вікно груп', 'вікна груп', 'вікон груп'],
  brokenDays: ['рваний день', 'рвані дні', 'рваних днів'],
  latePairs: ['пізня пара', 'пізні пари', 'пізніх пар'],
} as const;

/**
 * «Було» до запуску одним реченням: що генератор намагатиметься покращити. Нулі не називаємо;
 * плитки з числами не потрібні — після запуску з'явиться таблиця «Було і стало».
 */
export function baselineSentence(version: TimetableVersionDto): string {
  const m = version.metrics;
  const parts: string[] = [];
  if (version.hardConflicts > 0) parts.push(countLabel(version.hardConflicts, BASELINE_FORMS.hard));
  if (m) {
    for (const key of ['teacherWindows', 'groupWindows', 'brokenDays', 'latePairs'] as const) {
      if (m[key] > 0) parts.push(countLabel(m[key], BASELINE_FORMS[key]));
    }
  }
  return parts.length > 0 ? `${parts.join(', ')}.` : 'Конфліктів і вікон немає.';
}
