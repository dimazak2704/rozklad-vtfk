import type { BellProfileDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { bellColumns, breakAfter, pairMinutes, usualBreakMinutes, usualPairMinutes } from './bells-columns';

/** Нерозривний пробіл: «20 хв» не розривається між рядками. */
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
 * Дзвінки профілю таблицею: пари рядками, дні з однаковими дзвінками — колонками. Під часом — один тихий
 * рядок, лише коли є що сказати: «зараз»/«далі», незвична тривалість пари, довша за звичайну перерва
 * після пари («потім перерва 20 хв» — слово «потім» каже, що перерва йде після цієї пари, а не перед нею).
 * Звичайну перерву сторінка називає один раз під таблицею.
 * Сьогоднішня колонка тонована, поточна пара — тлом (без смужок прогресу: доріжку дня показує відлік).
 */
export function BellsTable({ profile, todayWeekday, live = false, className }: BellsTableProps) {
  const columns = bellColumns(profile);
  const usual = usualPairMinutes(profile);
  const usualBreak = usualBreakMinutes(profile);
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
                const longPair = minutes !== usual;
                const longBreak = gap !== null && usualBreak !== null && gap > usualBreak;
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
                    {state || longPair || longBreak ? (
                      // телефон: колонка вужча за «потім перерва 20 хв», тож примітка — коротка й одним рядком
                      // («перерва 20 хв» під часом пари і так читається як після неї), а стан — своїм рядком над нею;
                      // ширше — усе одним рядком через «·»
                      <span data-bell-note="" className="mt-0.5 flex flex-col text-caption text-muted figures sm:block">
                        {state ? <span className={cn('font-semibold', now ? 'text-accent' : 'text-secondary')}>{state}</span> : null}
                        {state && (longPair || longBreak) ? <span className="hidden sm:inline">{' · '}</span> : null}
                        {longPair || longBreak ? (
                          <span className="whitespace-nowrap">
                            {longPair ? `${minutes}${NBSP}хв` : null}
                            {longPair && longBreak ? ' · ' : null}
                            {longBreak ? (
                              <>
                                <span className="hidden sm:inline">потім </span>
                                {`перерва${NBSP}${gap}${NBSP}хв`}
                              </>
                            ) : null}
                          </span>
                        ) : null}
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
