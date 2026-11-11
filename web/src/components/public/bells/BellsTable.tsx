import type { BellProfileDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { bellColumns, breakAfter, pairMinutes, usualPairMinutes } from './bells-columns';

/** Нерозривний пробіл: «20 хв» не розривається між рядками. */
const NBSP = String.fromCharCode(0xa0);

/** Перерву пишемо лише довгу: короткі випливають із часу пар, а цифри без рішення — шум. */
export const LONG_BREAK_MINUTES = 20;

interface BellsTableProps {
  profile: BellProfileDto;
  /** День тижня ISO (1..7) сьогодні: його колонка виділена; null — без виділення. */
  todayWeekday: number | null;
  /** Підсвічувати поточну пару й наступну після перерви (лише для профілю, що діє). */
  live?: boolean;
  className?: string;
}

/**
 * Дзвінки профілю таблицею: пари рядками, дні з однаковими дзвінками — колонками. Під часом — один тихий
 * рядок, лише коли є що сказати: «зараз»/«далі», незвична тривалість пари, довга перерва (≥ 20 хв).
 * Сьогоднішня колонка тонована, поточна пара — тлом (без смужок прогресу: доріжку дня показує відлік).
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
                  {/* «Пн–Чт» — одне ціле, не ламається на «Пн–» і «Чт»; «сьогодні» на телефоні — рядком нижче */}
                  <span className="whitespace-nowrap sm:hidden">{column.short}</span>
                  <span className="hidden whitespace-nowrap sm:inline">{column.label}</span>
                  {isToday ? <span className="block text-caption font-semibold text-accent sm:ms-2 sm:inline">сьогодні</span> : null}
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
                const note = [
                  minutes !== usual ? `${minutes}${NBSP}хв` : null,
                  gap !== null && gap >= LONG_BREAK_MINUTES ? `перерва ${gap}${NBSP}хв` : null,
                ]
                  .filter(Boolean)
                  .join(' · ');
                const state = now ? 'зараз' : upcoming ? 'далі' : null;
                return (
                  <td
                    key={column.label}
                    aria-current={now ? 'time' : undefined}
                    className={cn('px-3 py-3 align-top', now ? 'bg-selected' : isToday && 'bg-today-wash')}
                  >
                    <span className="block text-body font-semibold whitespace-nowrap text-primary figures">
                      {slot.startsAt}
                      <span className="font-normal text-muted">–</span>
                      {slot.endsAt}
                    </span>
                    {state || note ? (
                      <span className="mt-0.5 block text-caption text-muted figures">
                        {state ? <span className={cn('font-semibold', now ? 'text-accent' : 'text-secondary')}>{state}</span> : null}
                        {state && note ? ' · ' : null}
                        {note}
                      </span>
                    ) : null}
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
