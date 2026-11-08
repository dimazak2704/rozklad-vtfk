import { CircleCheck, Crosshair } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { TimetableConflict, TimetableConflictKind } from '@/api/timetable';
import { Button, Chip, ErrorState, SegmentedControl, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { CONFLICT_KIND_LABEL } from '../model/labels';

export type ConflictSeverityTab = 'HARD' | 'SOFT';

interface ConflictsPanelProps {
  hard: readonly TimetableConflict[] | undefined;
  soft: readonly TimetableConflict[] | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  tab: ConflictSeverityTab;
  onTabChange: (tab: ConflictSeverityTab) => void;
  onFocusConflict: (conflict: TimetableConflict) => void;
  className?: string;
}

const PAGE = 40;

/**
 * Конфлікти версії: жорсткі (блокують публікацію) і попередження. Фільтр за видом, клік — до клітинки
 * в сітці. Довгий список показуємо порціями, щоб панель лишалась швидкою.
 */
export function ConflictsPanel({ hard, soft, loading, error, onRetry, tab, onTabChange, onFocusConflict, className }: ConflictsPanelProps) {
  const [kind, setKind] = useState<TimetableConflictKind | null>(null);
  const [limit, setLimit] = useState(PAGE);
  const list = useMemo(() => (tab === 'HARD' ? hard : soft) ?? [], [tab, hard, soft]);
  const kinds = useMemo(() => {
    const counts = new Map<TimetableConflictKind, number>();
    for (const conflict of list) counts.set(conflict.kind, (counts.get(conflict.kind) ?? 0) + 1);
    return [...counts].sort((a, b) => b[1] - a[1]);
  }, [list]);
  const activeKind = kind && kinds.some(([k]) => k === kind) ? kind : null;
  const filtered = activeKind ? list.filter((c) => c.kind === activeKind) : list;

  const switchTab = (next: ConflictSeverityTab) => {
    onTabChange(next);
    setKind(null);
    setLimit(PAGE);
  };

  return (
    <section aria-labelledby="conflicts-title" className={cn('flex min-h-0 flex-col rounded-panel border border-default bg-surface', className)}>
      <header className="flex flex-col gap-2.5 border-b border-separator px-3 py-2.5">
        <h2 id="conflicts-title" className="text-heading font-semibold text-primary">
          Конфлікти
        </h2>
        <SegmentedControl<ConflictSeverityTab>
          aria-label="Вид конфліктів"
          size="sm"
          stretch
          value={tab}
          onValueChange={switchTab}
          options={[
            { value: 'HARD', label: `Жорсткі · ${hard?.length ?? '…'}` },
            { value: 'SOFT', label: `Попередження · ${soft?.length ?? '…'}` },
          ]}
        />
        {kinds.length > 1 ? (
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Фільтр за видом">
            {kinds.map(([value, count]) => (
              <Chip
                key={value}
                selected={activeKind === value}
                onToggle={() => {
                  setKind(activeKind === value ? null : value);
                  setLimit(PAGE);
                }}
                count={count}
                className="h-7 text-caption"
              >
                {CONFLICT_KIND_LABEL[value]}
              </Chip>
            ))}
          </div>
        ) : null}
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {loading ? (
          <div className="flex flex-col gap-3 p-3" aria-hidden="true">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : error ? (
          <ErrorState size="sm" error={error} onRetry={onRetry} title="Конфлікти не завантажились" />
        ) : filtered.length === 0 ? (
          <p className="flex items-start gap-2 px-3 py-6 text-ui text-secondary">
            <CircleCheck aria-hidden="true" className="mt-0.5 size-4 text-add" />
            {tab === 'HARD'
              ? 'Жорстких конфліктів немає — версію можна публікувати.'
              : 'Попереджень немає: без вікон, рваних днів і перевантажених днів.'}
          </p>
        ) : (
          <ul className="divide-y divide-[var(--border-color-separator)]">
            {filtered.slice(0, limit).map((conflict, index) => (
              <li key={`${conflict.kind}-${conflict.message}-${index}`}>
                <button
                  type="button"
                  onClick={() => onFocusConflict(conflict)}
                  className="group/conflict flex w-full items-start gap-2 px-3 py-2 text-start transition-colors duration-100 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
                >
                  <span
                    aria-hidden="true"
                    className={cn('mt-1.5 size-2 shrink-0 rounded-full', conflict.severity === 'HARD' ? 'bg-[var(--fill-cancel)]' : 'bg-[var(--fill-change)]')}
                  />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="text-meta font-semibold text-muted">{CONFLICT_KIND_LABEL[conflict.kind]}</span>
                    <span className="text-caption text-primary">{conflict.message}</span>
                  </span>
                  <Crosshair
                    aria-hidden="true"
                    className="mt-1 size-3.5 shrink-0 text-muted opacity-0 transition-opacity duration-100 group-hover/conflict:opacity-100 group-focus-visible/conflict:opacity-100"
                  />
                  <span className="sr-only">Показати в сітці</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {!loading && filtered.length > limit ? (
          <div className="border-t border-separator p-2">
            <Button size="sm" variant="ghost" className="w-full" onClick={() => setLimit((current) => current + PAGE)}>
              Показати ще {Math.min(PAGE, filtered.length - limit)} з {filtered.length - limit}
            </Button>
          </div>
        ) : null}
      </div>
    </section>
  );
}
