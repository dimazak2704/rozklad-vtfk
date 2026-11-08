import type { BellProfileDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { bellColumns, breakAfter, pairMinutes } from './bells-columns';

/** Ледь помітне тло сьогоднішньої колонки (половина «вибраного»). */
const TODAY_TINT = 'bg-[color-mix(in_oklab,var(--background-color-selected)_45%,transparent)]';

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
 * Сьогоднішня колонка виділена, поточна пара — «живим» кольором зі смужкою прогресу.
 */
export function BellsTable({ profile, todayWeekday, live = false, className }: BellsTableProps) {
  const columns = bellColumns(profile);
  const todayColumn = columns.find((column) => column.weekdays.some((day) => day === todayWeekday)) ?? null;
  const { status } = useNow(live && todayColumn ? todayColumn.slots : null);
  const currentPair = status?.kind === 'in-pair' ? status.current.pair : null;
  const upcomingPair = status?.kind === 'break' || status?.kind === 'before-day' ? status.next.pair : null;
  const progress = status?.kind === 'in-pair' ? status.progress : 0;
  const maxPairs = Math.max(0, ...columns.map((column) => column.slots.length));
  const pairs = Array.from({ length: maxPairs }, (_, index) => index + 1);

  return (
    <div className={cn('overflow-x-auto rounded-panel border border-default bg-surface', className)}>
      <table className="w-full min-w-[18rem] border-collapse text-start">
        <caption className="sr-only">Дзвінки: {profile.name}</caption>
        <thead>
          <tr className="bg-sunken">
            <th scope="col" className="w-16 border-b border-default px-3 py-2.5 text-start text-caption font-semibold text-secondary">
              Пара
            </th>
            {columns.map((column) => {
              const isToday = column === todayColumn;
              return (
                <th
                  key={column.label}
                  scope="col"
                  aria-current={isToday ? 'date' : undefined}
                  className={cn(
                    'border-b px-3 py-2.5 text-start text-ui font-semibold',
                    isToday ? 'border-b-2 border-b-[var(--border-color-accent)] bg-selected text-primary' : 'border-default text-primary',
                  )}
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
              <th scope="row" className="px-3 py-2.5 text-start align-top">
                <span className={cn('font-condensed text-headline leading-none font-bold figures', pair === currentPair && todayColumn ? 'text-accent' : 'text-primary')}>
                  {pair}
                </span>
              </th>
              {columns.map((column) => {
                const slot = column.slots.find((s) => s.pair === pair);
                const isToday = column === todayColumn;
                const now = isToday && pair === currentPair;
                const upcoming = isToday && pair === upcomingPair;
                const gap = slot ? breakAfter(column.slots, pair) : null;
                if (!slot) {
                  return (
                    <td key={column.label} className={cn('px-3 py-2.5 align-top text-caption text-disabled', isToday && TODAY_TINT)}>
                      пари немає
                    </td>
                  );
                }
                return (
                  <td
                    key={column.label}
                    aria-current={now ? 'time' : undefined}
                    className={cn('relative px-3 py-2.5 align-top', now && 'bg-selected', isToday && !now && TODAY_TINT)}
                  >
                    {now ? (
                      <span
                        aria-hidden="true"
                        className="absolute inset-x-0 top-0 h-0.5 origin-left bg-accent motion-safe:transition-transform motion-safe:duration-1000 motion-safe:ease-linear rtl:origin-right"
                        style={{ transform: `scaleX(${Math.max(0.02, progress)})` }}
                      />
                    ) : null}
                    <span className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-body font-semibold text-primary figures">
                        {slot.startsAt}
                        <span className="font-normal text-muted">–</span>
                        {slot.endsAt}
                      </span>
                      {now ? <span className="text-caption font-semibold text-accent">зараз</span> : null}
                      {upcoming ? <span className="text-caption font-semibold text-secondary">далі</span> : null}
                    </span>
                    <span className="mt-0.5 block text-meta font-normal text-muted figures">
                      {pairMinutes(slot)} хв{gap !== null ? ` · перерва ${gap} хв` : ''}
                    </span>
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
