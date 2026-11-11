import { useOrders } from '@/api/admin-daily';
import type { IsoDate, OrderSummaryDto } from '@/api/types';
import { cn } from '@/lib/cn';
import { addDays, formatWeekdayDate, startOfWeek } from '@/lib/date';
import { weekdayShort } from '@/lib/labels';

type DayState = 'published' | 'draft' | 'none';

function dayStatus(orders: readonly OrderSummaryDto[]): { state: DayState; text: string } {
  const published = orders.filter((o) => o.status === 'PUBLISHED').sort((a, b) => (a.number ?? 0) - (b.number ?? 0));
  const draft = orders.find((o) => o.status === 'DRAFT' && o.itemCount > 0);
  if (published.length > 0) {
    const numbers = published.map((o) => `№${o.number ?? '—'}`).join(', ');
    return { state: 'published', text: draft ? `опубліковано ${numbers}, є нова чернетка` : `опубліковано ${numbers}` };
  }
  if (draft) return { state: 'draft', text: 'чернетка' };
  return { state: 'none', text: 'змін немає' };
}

const DOT: Record<DayState, string> = {
  published: 'bg-[var(--fill-accent)]',
  draft: 'border-[1.5px] border-[var(--fill-change)] bg-transparent',
  none: 'bg-transparent',
};

interface WeekStripProps {
  date: IsoDate;
  onSelect: (date: IsoDate) => void;
}

/**
 * Тиждень робочої дати одним рядком: день і крапка під ним — синя, якщо розпорядження опубліковане,
 * бурштинове кільце — якщо лежить чернетка. Подробиці — у підказці й для читача екрана.
 */
export function WeekStrip({ date, onSelect }: WeekStripProps) {
  const monday = startOfWeek(date);
  const days = [0, 1, 2, 3, 4].map((offset) => addDays(monday, offset));
  const orders = useOrders({ from: days[0], to: days[4], size: 50 });
  return (
    <nav aria-label="Розпорядження тижня" className="flex gap-1">
      {days.map((day, index) => {
        const status = dayStatus((orders.data?.items ?? []).filter((o) => o.date === day));
        const selected = day === date;
        const label = `${formatWeekdayDate(day)}: ${orders.isPending ? 'завантажуємо' : status.text}`;
        return (
          <button
            key={day}
            type="button"
            aria-current={selected ? 'date' : undefined}
            aria-label={label}
            title={label}
            onClick={() => onSelect(day)}
            className={cn(
              'flex h-14 min-w-12 flex-1 flex-col items-center justify-center gap-0.5 rounded-control sm:max-w-20',
              'transition-[background-color] duration-150 ease-out focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
              selected ? 'bg-selected' : 'hover:bg-hover',
            )}
          >
            <span className={cn('text-meta', selected ? 'font-semibold text-accent' : 'text-muted')}>{weekdayShort(index + 1)}</span>
            <span className={cn('text-ui figures', selected ? 'font-semibold text-primary' : 'text-secondary')}>{Number(day.slice(8))}</span>
            <span aria-hidden="true" className={cn('size-1.5 rounded-full', orders.isPending ? 'bg-transparent' : DOT[status.state])} />
          </button>
        );
      })}
    </nav>
  );
}
