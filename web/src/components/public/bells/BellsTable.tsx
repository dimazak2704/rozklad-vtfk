import type { BellProfileDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { bellColumns, breakAfter, pairMinutes, usualPairMinutes } from './bells-columns';

/** Нерозривний пробіл: «10 хв» не розривається між рядками. */
const NBSP = String.fromCharCode(0xa0);

interface BellsTableProps {
  profile: BellProfileDto;
  /** День тижня ISO (1..7) сьогодні: його колонка виділена; null — без виділення. */
  todayWeekday: number | null;
  /** Підсвічувати поточну пару й наступну після перерви (лише для профілю, що діє). */
  live?: boolean;
  className?: string;
}

/**
 * Дзвінки профілю таблицею: пари рядками, дні з однаковими дзвінками — колонками; під часом — перерва.
 * Тривалість пари пишемо лише тоді, коли вона не звичайна. Сьогоднішня колонка тонована, поточна пара —
 * тлом і словом «зараз» (без смужок прогресу: «живу» доріжку дня показує відлік над таблицею).
 */
export function BellsTable({ profile, todayWeekday, live = false, className }: BellsTableProps) {
  const columns = bellColumns(profile);
  const usual = usualPairMinutes(profile);
  const todayColumn = columns.find((column) => column.weekdays.some((day) => day === todayWeekday)) ?? null;
  const { status } = useNow(live && todayColumn ? todayColumn.slots : null);
  const currentPair = status?.kind === 'in-pair' ? status.current.pair : null;
  const upcomingPair = status?.kind === 'break' || status?.kind === 'before-day' ? status.next.pair : null;
  const maxPairs = Math.max(0, ...columns.map((column) => column.slots.length));
  const pairs = Array.from({ length: maxPairs }, (_, index) => index + 1);

  return (
    <div className={cn('overflow-x-auto rounded-panel border border-default bg-surface', className)}>
      <table className="w-full min-w-[17rem] border-collapse text-start">
        <caption className="sr-only">Дзвінки: {profile.name}</caption>
        <thead>
          <tr>
            <th scope="col" className="w-14 border-b border-separator px-4 py-3 text-start text-caption font-semibold text-secondary sm:w-20">
              Пара
            </th>
            {columns.map((column) => {
              const isToday = column === todayColumn;
              return (
                <th
                  key={column.label}
                  scope="col"
                  aria-current={isToday ? 'date' : undefined}
                  className={cn('border-b border-separator px-3 py-3 text-start text-ui font-semibold text-primary', isToday && 'bg-today-wash')}
                >
                  <span className="sm:hidden">{column.short}</span>
                  <span className="hidden sm:inline">{column.label}</span>
                  {isToday ? <span className="ms-2 text-caption font-semibold text-accent">сьогодні</span> : null}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair) => (
            <tr key={pair} className="border-b border-separator last:border-b-0">
              <th scope="row" className="px-4 py-3 text-start align-top">
                <span className={cn('font-display text-title font-semibold figures', pair === currentPair && todayColumn ? 'text-accent' : 'text-primary')}>
                  {pair}
                </span>
              </th>
              {columns.map((column) => {
                const slot = column.slots.find((s) => s.pair === pair);
                const isToday = column === todayColumn;
                const now = isToday && pair === currentPair;
                const upcoming = isToday && pair === upcomingPair;
                if (!slot) {
                  return (
                    <td key={column.label} className={cn('px-3 py-3 align-top text-caption text-muted', isToday && 'bg-today-wash')}>
                      пари немає
                    </td>
                  );
                }
                const gap = breakAfter(column.slots, pair);
                const minutes = pairMinutes(slot);
                const note = [minutes !== usual ? `${minutes}${NBSP}хв` : null, gap !== null ? `перерва ${gap}${NBSP}хв` : null]
                  .filter(Boolean)
                  .join(' · ');
                return (
                  <td
                    key={column.label}
                    aria-current={now ? 'time' : undefined}
                    className={cn('px-3 py-3 align-top', now ? 'bg-selected' : isToday && 'bg-today-wash')}
                  >
                    <span className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-body font-semibold whitespace-nowrap text-primary figures">
                        {slot.startsAt}
                        <span className="font-normal text-muted">–</span>
                        {slot.endsAt}
                      </span>
                      {now ? <span className="text-caption font-semibold text-accent">зараз</span> : null}
                      {upcoming ? <span className="text-caption font-semibold text-secondary">далі</span> : null}
                    </span>
                    {note ? <span className="mt-0.5 block text-caption text-muted figures">{note}</span> : null}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
