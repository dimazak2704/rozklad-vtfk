import type { BellSlotDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { timeToMinutes } from '@/lib/date';

interface BellsCompactProps {
  slots: readonly BellSlotDto[];
  /** Сьогоднішні дзвінки: підсвітити поточну пару й наступну після перерви. */
  live?: boolean;
  /** Показувати перерви між парами (на головній — без них, коротше). */
  showBreaks?: boolean;
  className?: string;
}

/** Дзвінки дня короткою колонкою: номер пари, час, перерва; поточна пара — «живим» кольором. */
export function BellsCompact({ slots, live = false, showBreaks = true, className }: BellsCompactProps) {
  const { status } = useNow(live ? slots : null);
  const current = status?.kind === 'in-pair' ? status.current.pair : null;
  const upcoming = status?.kind === 'break' || status?.kind === 'before-day' ? status.next.pair : null;
  const sorted = [...slots].sort((a, b) => a.pair - b.pair);

  return (
    <ol className={cn('flex flex-col', className)}>
      {sorted.map((slot, index) => {
        const next = sorted[index + 1];
        const gap = next ? timeToMinutes(next.startsAt) - timeToMinutes(slot.endsAt) : null;
        const isCurrent = slot.pair === current;
        const isUpcoming = slot.pair === upcoming;
        return (
          <li key={slot.pair} aria-current={isCurrent ? 'time' : undefined}>
            <div
              className={cn(
                'grid grid-cols-[1.75rem_minmax(0,1fr)_auto] items-baseline gap-2 rounded-control px-2 py-1',
                isCurrent && 'bg-selected',
              )}
            >
              <span className={cn('font-condensed text-heading leading-none font-bold figures', isCurrent ? 'text-accent' : 'text-primary')}>
                {slot.pair}
              </span>
              <span className="text-ui text-primary figures">
                {slot.startsAt}
                <span className="text-muted">–</span>
                {slot.endsAt}
              </span>
              <span className="text-meta font-semibold">
                {isCurrent ? <span className="text-accent">зараз</span> : isUpcoming ? <span className="text-secondary">далі</span> : null}
              </span>
            </div>
            {showBreaks && gap !== null && gap > 0 ? (
              <p className="ps-11 text-meta font-normal text-muted figures">перерва {gap}&nbsp;хв</p>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}
