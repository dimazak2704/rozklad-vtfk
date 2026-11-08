/** Генератор: параметри форми, перетворення в запит, тривалості, рядки «Було / Стало». */
import { DEFAULT_WEIGHTS, TIME_LIMIT_MAX, TIME_LIMIT_MIN } from '@/api/generation';
import type { GenerationJobDto, GenerationJobRequest, GenerationWeights, MetricsDto, Uuid } from '@/api/types';

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
  before: number;
  after: number;
}

/** Рядки «Було / Стало»: жорсткі конфлікти (з оцінки) і метрики версії. */
export function comparisonRows(job: GenerationJobDto): ComparisonRow[] {
  const before = job.metricsBefore;
  const after = job.metricsAfter;
  if (!before || !after) return [];
  const first = job.progress[0];
  const last = job.progress.at(-1);
  const rows: ComparisonRow[] = [];
  if (first && last) rows.push({ key: 'hard', label: 'Жорсткі конфлікти', before: -first.hard, after: -last.hard });
  const metric = (key: keyof Omit<MetricsDto, 'perGroup'>, label: string) =>
    rows.push({ key, label, before: before[key], after: after[key] });
  metric('groupWindows', 'Вікна груп');
  metric('teacherWindows', 'Вікна викладачів');
  metric('brokenDays', 'Рвані дні');
  metric('latePairs', 'Пізні пари');
  metric('maxPairsPerDay', 'Макс. пар на день');
  return rows;
}

/** Пояснення ваг для повзунків. */
export const WEIGHT_FIELDS: ReadonlyArray<{ key: keyof GenerationWeights; label: string; hint: string }> = [
  { key: 'windows', label: 'Вікна', hint: 'Штраф за кожну порожню пару між заняттями групи чи викладача в один день.' },
  { key: 'brokenDays', label: 'Рвані дні', hint: 'Штраф за день, коли в чисельник і знаменник різна кількість пар або різна перша пара.' },
  { key: 'latePairs', label: 'Пізні пари', hint: 'Штраф за заняття після 16:00 (7-ма і 8-ма пари).' },
  { key: 'preferences', label: 'Побажання викладачів', hint: 'Штраф за пару в слоті, який викладач позначив як небажаний.' },
  { key: 'homeRoom', label: 'Аудиторія викладача', hint: 'Штраф, коли пара не в аудиторії, де викладач зазвичай веде цю дисципліну.' },
];

export const WEIGHT_MAX = 30;
