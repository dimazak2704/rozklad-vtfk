import { useMemo } from 'react';
import type { BellProfileDto, IsoDate, ScheduleDto } from '@/api/types';
import { StatusDot } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatShortWeekdayDate } from '@/lib/date';
import { weekdayShort } from '@/lib/labels';
import { roomOccupancy } from './schedule-utils';

interface RoomOccupancyProps {
  schedule: ScheduleDto;
  roomId: string;
  /** Профіль дзвінків, що діє: скільки пар у кожен день. */
  profile: BellProfileDto;
  today: IsoDate;
  className?: string;
}

/**
 * Зайнятість аудиторії за тиждень: сітка «день × пара» і відсоток, а сьогодні — чи вільна вона просто зараз.
 * Скасовані пари й ті, що переїхали в іншу аудиторію, її не займають.
 */
export function RoomOccupancy({ schedule, roomId, profile, today, className }: RoomOccupancyProps) {
  const occupancy = useMemo(
    () => roomOccupancy(schedule, roomId, (weekday) => profile.slots.filter((slot) => slot.weekday === weekday).length),
    [schedule, roomId, profile],
  );
  const todayBells = useMemo(() => {
    const weekday = schedule.days.find((d) => d.date === today)?.weekday;
    return weekday ? profile.slots.filter((slot) => slot.weekday === weekday) : null;
  }, [schedule, today, profile]);
  const { status } = useNow(todayBells, { date: today });
  const todayRow = occupancy.days.find((d) => d.date === today);
  const percent = occupancy.total > 0 ? Math.round((occupancy.busy / occupancy.total) * 100) : 0;

  let live: string | null = null;
  if (todayRow && status && (status.kind === 'in-pair' || status.kind === 'break' || status.kind === 'before-day')) {
    const pair = status.kind === 'in-pair' ? status.current.pair : status.next.pair;
    const busyNow = status.kind === 'in-pair' && todayRow.pairs.some((p) => p.pair === pair && p.busy);
    const nextBusy = todayRow.pairs.find((p) => p.busy && p.pair >= (status.kind === 'in-pair' ? pair + 1 : pair));
    if (busyNow) live = `Зараз іде ${pair} пара — аудиторія зайнята`;
    else if (nextBusy) live = `Зараз вільна, наступне заняття — ${nextBusy.pair} пара`;
    else live = 'Зараз вільна до кінця дня';
  }

  return (
    <section
      aria-label="Зайнятість аудиторії"
      className={cn('grid gap-x-8 gap-y-4 rounded-panel border border-default bg-surface p-4 md:grid-cols-[minmax(0,1fr)_auto]', className)}
    >
      <div className="flex min-w-0 flex-col gap-3">
        <h2 className="text-heading font-semibold text-primary">Зайнятість цього тижня</h2>
        <p className="flex items-baseline gap-2 text-ui text-secondary figures">
          <span className="font-condensed text-display leading-none font-bold text-primary">{percent}%</span>
          <span>
            зайнято <span className="font-semibold text-primary">{occupancy.busy}</span> з {occupancy.total} пар
          </span>
        </p>
        {live ? (
          <p className="flex items-center gap-2 text-ui text-primary">
            <StatusDot tone="live" pulse label="Наживо" />
            {live}
          </p>
        ) : null}
        <p className="mt-auto flex flex-wrap gap-x-4 gap-y-1 text-caption text-muted">
          <span className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className="size-3 rounded-plate bg-inverse" /> зайнята
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className="size-3 rounded-plate border border-default bg-sunken" /> вільна
          </span>
        </p>
      </div>
      <table className="w-max self-start border-separate border-spacing-1">
        <caption className="sr-only">Зайняті пари аудиторії за днями тижня</caption>
        <thead>
          <tr>
            <th scope="col" className="sr-only">
              День
            </th>
            {Array.from({ length: Math.max(0, ...occupancy.days.map((d) => d.pairs.length)) }, (_, index) => (
              <th key={index} scope="col" className="w-5 text-center text-meta font-semibold text-muted figures">
                {index + 1}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {occupancy.days.map((day) => (
            <tr key={day.date}>
              <th scope="row" className={cn('pe-2 text-start text-caption font-semibold', day.date === today ? 'text-accent' : 'text-secondary')}>
                <abbr title={formatShortWeekdayDate(day.date)} className="no-underline">
                  {weekdayShort(day.weekday)}
                </abbr>
              </th>
              {day.pairs.map((p) => (
                <td key={p.pair} className="p-0">
                  <span
                    aria-label={`${p.pair} пара: ${p.busy ? 'зайнята' : 'вільна'}`}
                    className={cn(
                      'block size-5 rounded-plate border',
                      p.busy ? 'border-transparent bg-inverse' : 'border-default bg-sunken',
                      day.date === today && status?.kind === 'in-pair' && status.current.pair === p.pair && 'ring-2 ring-[var(--border-color-accent)] ring-offset-1',
                    )}
                  />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
