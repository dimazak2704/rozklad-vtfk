import { useQueryClient } from '@tanstack/react-query';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { useEffect, useState } from 'react';
import { qk } from '@/api/queryKeys';
import type { MetricsDto, Uuid } from '@/api/types';
import { Skeleton, Tooltip } from '@/components/ui';
import { cn } from '@/lib/cn';
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

const CELL =
  'flex min-w-0 flex-col gap-0.5 bg-surface px-4 py-2.5 text-start focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus';

interface MetricsBarProps {
  versionId: Uuid;
  metrics: MetricsDto | undefined;
  /** Метрики опублікованої версії — для порівняння. */
  baseline: MetricsDto | null;
  hardConflicts: number | undefined;
  loading: boolean;
  onShowConflicts: () => void;
}

/**
 * Табло якості версії: жорсткі конфлікти, вікна, рвані дні, пізні пари. Підказка пояснює кожну метрику;
 * поруч — різниця з опублікованою версією, а одразу після правки — що саме змінилось.
 */
export function MetricsBar({ versionId, metrics, baseline, hardConflicts, loading, onShowConflicts }: MetricsBarProps) {
  const recent = useRecentDeltas(versionId);

  return (
    <section
      aria-label="Якість версії"
      className="grid grid-cols-2 gap-px overflow-hidden rounded-panel border border-default bg-[var(--border-color-separator)] sm:grid-cols-3 xl:grid-cols-6"
    >
      <Tooltip content="Накладки викладачів, груп і аудиторій, пари поза зміною. Поки вони є, версію не опублікувати. Натисніть, щоб побачити список.">
        <button type="button" onClick={onShowConflicts} className={cn(CELL, 'transition-colors duration-100 hover:bg-hover')}>
          {loading || hardConflicts === undefined ? (
            <Skeleton className="h-7 w-10" />
          ) : (
            <span className={cn('font-condensed text-headline leading-none font-bold figures', hardConflicts > 0 ? 'text-danger' : 'text-add')}>
              {hardConflicts}
            </span>
          )}
          <span className="truncate text-caption text-secondary">Жорсткі конфлікти</span>
        </button>
      </Tooltip>

      {METRICS.map(({ key, label, hint }) => {
        const value = metrics?.[key];
        const vsBaseline = baseline && metrics && key !== 'maxPairsPerDay' ? metrics[key] - baseline[key] : 0;
        const changed = recent[key];
        return (
          <Tooltip key={key} content={hint} side="bottom">
            <div tabIndex={0} role="group" data-metric={key} aria-label={value === undefined ? `${label}: завантажуємо` : `${label}: ${value}`} className={CELL}>
              <span className="flex items-baseline gap-2">
                {loading || value === undefined ? (
                  <Skeleton className="h-7 w-10" />
                ) : (
                  <span className="font-condensed text-headline leading-none font-bold text-primary figures">{value}</span>
                )}
                {changed ? (
                  <span key={`${key}-${changed}`} className="motion-safe:animate-[fade-in_200ms_var(--ease-out)]">
                    <Delta value={changed} context="Після правки" />
                  </span>
                ) : vsBaseline !== 0 ? (
                  <Delta value={vsBaseline} context="Порівняно з опублікованою" />
                ) : null}
              </span>
              <span className="truncate text-caption text-secondary">{label}</span>
            </div>
          </Tooltip>
        );
      })}
    </section>
  );
}
