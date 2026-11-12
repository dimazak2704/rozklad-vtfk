import { useQueryClient } from '@tanstack/react-query';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { useEffect, useState } from 'react';
import { qk } from '@/api/queryKeys';
import type { MetricsDto, Uuid } from '@/api/types';
import { Disclosure } from '@/components/generation/Disclosure';
import { Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { METRICS, type MetricKey } from '../model/labels';

type Deltas = Partial<Record<MetricKey, number>>;

const DELTA_VISIBLE_MS = 6000;

function diffMetrics(before: MetricsDto, after: MetricsDto): Deltas {
  const out: Deltas = {};
  for (const { key } of METRICS) {
    const delta = after[key] - before[key];
    if (delta !== 0) out[key] = delta;
  }
  return out;
}

/**
 * Що змінилось у метриках після останньої правки: стежимо за кешем запиту метрик цієї версії
 * і кілька секунд показуємо «−1» / «+2» біля числа.
 */
function useRecentDeltas(versionId: Uuid): Deltas {
  const queryClient = useQueryClient();
  const [deltas, setDeltas] = useState<{ versionId: Uuid; values: Deltas } | null>(null);

  useEffect(() => {
    const key = JSON.stringify(qk.admin.timetable.metrics(versionId));
    let previous = queryClient.getQueryData<MetricsDto>(qk.admin.timetable.metrics(versionId));
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
      if (event.type !== 'updated' || event.action.type !== 'success') return;
      if (JSON.stringify(event.query.queryKey) !== key) return;
      const next = event.query.state.data as MetricsDto | undefined;
      if (previous && next) {
        const values = diffMetrics(previous, next);
        if (Object.keys(values).length > 0) {
          setDeltas({ versionId, values });
          clearTimeout(timer);
          timer = setTimeout(() => setDeltas(null), DELTA_VISIBLE_MS);
        }
      }
      previous = next;
    });
    return () => {
      unsubscribe();
      clearTimeout(timer);
    };
  }, [queryClient, versionId]);

  return deltas?.versionId === versionId ? deltas.values : {};
}

/** «↓21» зеленим, якщо стало менше (краще), «↑3» червоним — якщо більше. */
function Delta({ value, context }: { value: number; context: string }) {
  const better = value < 0;
  return (
    <span
      className={cn('inline-flex items-center gap-0.5 text-meta font-semibold figures', better ? 'text-add' : 'text-danger')}
      aria-label={`${context}: ${better ? 'менше' : 'більше'} на ${Math.abs(value)}`}
    >
      {better ? <ArrowDown aria-hidden="true" className="size-3" /> : <ArrowUp aria-hidden="true" className="size-3" />}
      {Math.abs(value)}
    </span>
  );
}

interface MetricsBarProps {
  versionId: Uuid;
  metrics: MetricsDto | undefined;
  /** Метрики опублікованої версії — для порівняння. */
  baseline: MetricsDto | null;
  hardConflicts: number | undefined;
  softWarnings: number | undefined;
  loading: boolean;
  /** Чернетка: речення каже, чи конфлікти заважають публікації. */
  publishable?: boolean;
  onShowConflicts: () => void;
}

const CONFLICT_FORMS = ['конфлікт', 'конфлікти', 'конфліктів'] as const;

/**
 * Якість версії одним реченням з одним числом: «75 конфліктів не дають опублікувати — показати».
 * Вікна, рвані дні й пізні пари — у розгортці «Подробиці», і лише ненульові. Одразу після правки
 * речення каже, що змінилось. На телефоні — один рядок без подробиць.
 */
export function MetricsBar({ versionId, metrics, baseline, hardConflicts, softWarnings, loading, publishable = false, onShowConflicts }: MetricsBarProps) {
  const recent = useRecentDeltas(versionId);
  const recentKeys = METRICS.filter(({ key }) => recent[key] !== undefined);

  if (loading || hardConflicts === undefined || !metrics) {
    return (
      <div aria-label="Якість версії" className="flex flex-col gap-2">
        <Skeleton className="h-5 w-3/4 max-w-xl" />
      </div>
    );
  }

  // нулі не показуємо: «Вікна груп 0» — шум, а не інформація
  const details = METRICS.filter(({ key }) => metrics[key] > 0);

  return (
    <section aria-label="Якість версії" className="flex flex-col gap-1">
      <p className="text-body text-secondary">
        {hardConflicts > 0 ? (
          <>
            <span className="font-[650] text-primary">{countLabel(hardConflicts, CONFLICT_FORMS)}</span>
            <span className="max-md:hidden">{publishable ? ' не дають опублікувати' : ' у цій версії'}</span>
            {' — '}
            <button
              type="button"
              onClick={onShowConflicts}
              aria-label={`Конфлікти: ${hardConflicts} жорстких${softWarnings ? `, ${softWarnings} попереджень` : ''} — показати список`}
              className="rounded-tag font-[650] text-primary underline decoration-1 underline-offset-4 hover:decoration-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
            >
              показати
            </button>
            .
          </>
        ) : (
          // попередження (вікна, рвані дні) лишаються в панелі «Конфлікти», тож тут кажемо саме про жорсткі —
          // інакше «Конфліктів немає» поруч із «Попередження · 160» звучить як суперечність
          <span>
            {softWarnings ? 'Жорстких конфліктів немає' : 'Конфліктів немає'}
            {publishable ? ' — версію можна публікувати.' : '.'}
          </span>
        )}
        {recentKeys.length > 0 ? (
          <span key={JSON.stringify(recent)} className="ms-2 inline-flex flex-wrap items-center gap-2 text-caption motion-safe:animate-[fade-in_200ms_var(--ease-out)]">
            <span className="text-muted">Після правки:</span>
            {recentKeys.map(({ key, label }) => (
              <span key={key} className="inline-flex items-center gap-1">
                {label.toLocaleLowerCase('uk')}
                <Delta value={recent[key] ?? 0} context={label} />
              </span>
            ))}
          </span>
        ) : null}
      </p>
      {details.length > 0 ? (
        <Disclosure summary="Подробиці" className="max-md:hidden">
          <dl className="grid max-w-3xl grid-cols-1 gap-x-8 gap-y-2 ps-5.5 sm:grid-cols-2">
            {details.map(({ key, label, hint }) => {
              const value = metrics[key];
              const vsBaseline = baseline && key !== 'maxPairsPerDay' ? value - baseline[key] : 0;
              return (
                <div key={key} role="group" data-metric={key} aria-label={`${label}: ${value}`} className="flex flex-col gap-0.5">
                  <dt className="flex items-baseline gap-2 text-ui text-primary">
                    {label}
                    <span className="figures font-semibold">{value}</span>
                    {vsBaseline !== 0 ? <Delta value={vsBaseline} context="Порівняно з опублікованою" /> : null}
                  </dt>
                  <dd className="text-caption text-muted">{hint}</dd>
                </div>
              );
            })}
          </dl>
          {baseline ? <p className="mt-2 ps-5.5 text-caption text-muted">Стрілки — різниця з опублікованою версією.</p> : null}
        </Disclosure>
      ) : null}
    </section>
  );
}
