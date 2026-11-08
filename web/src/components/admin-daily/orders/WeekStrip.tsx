import { useOrders } from '@/api/admin-daily';
import type { IsoDate, OrderSummaryDto } from '@/api/types';
import { cn } from '@/lib/cn';
import { addDays, formatShortWeekdayDate, startOfWeek } from '@/lib/date';

function dayStatus(orders: readonly OrderSummaryDto[]): { text: string; tone: string } {
  const published = orders.filter((o) => o.status === 'PUBLISHED').sort((a, b) => (a.number ?? 0) - (b.number ?? 0));
  const draft = orders.find((o) => o.status === 'DRAFT' && o.itemCount > 0);
  if (published.length > 0) {
    const numbers = published.map((o) => `№${o.number ?? '—'}`).join(', ');
    return { text: draft ? `${numbers} + чернетка` : numbers, tone: 'text-accent' };
  }
  if (draft) return { text: `чернетка · ${draft.itemCount}`, tone: 'text-change' };
  return { text: '—', tone: 'text-muted' };
}

interface WeekStripProps {
  date: IsoDate;
  onSelect: (date: IsoDate) => void;
}

/** Тиждень робочої дати: на який день що вже опубліковано, де лежить чернетка. Клік — перейти до дня. */
export function WeekStrip({ date, onSelect }: WeekStripProps) {
  const monday = startOfWeek(date);
  const days = [0, 1, 2, 3, 4].map((offset) => addDays(monday, offset));
  const orders = useOrders({ from: days[0], to: days[4], size: 50 });
  return (
    <nav aria-label="Розпорядження тижня" className="grid grid-cols-5 gap-1.5">
      {days.map((day) => {
        const status = dayStatus((orders.data?.items ?? []).filter((o) => o.date === day));
        const selected = day === date;
        return (
          <button
            key={day}
            type="button"
            aria-current={selected ? 'date' : undefined}
            onClick={() => onSelect(day)}
            className={cn(
              'flex min-w-0 flex-col items-start gap-0.5 rounded-control border px-2.5 py-1.5 text-start',
              'transition-[background-color,border-color] duration-100 ease-out focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
              selected ? 'border-accent bg-selected' : 'border-default bg-surface hover:border-strong hover:bg-hover',
            )}
          >
            <span className={cn('text-caption font-semibold figures', selected ? 'text-accent' : 'text-primary')}>{formatShortWeekdayDate(day)}</span>
            <span className={cn('max-w-full truncate text-meta figures', orders.isPending ? 'text-muted' : status.tone)}>
              {orders.isPending ? '…' : status.text}
            </span>
          </button>
        );
      })}
    </nav>
  );
}
