import { useMemo } from 'react';
import type { BellProfileDto, IsoDate, ScheduleDto } from '@/api/types';
import { StatusDot } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { roomOccupancy } from './schedule-utils';

interface RoomNowStatusProps {
  schedule: ScheduleDto;
  roomId: string;
  /** Профіль дзвінків, що діє: скільки пар у кожен день. */
  profile: BellProfileDto;
  today: IsoDate;
  className?: string;
}

/**
 * Чи вільна аудиторія просто зараз — одне речення в рядку контексту заголовка. Зайнятість за тиждень
 * і так видно в сітці, тож окремої «теплової карти» й відсотків немає. Скасовані пари й ті, що переїхали
 * в іншу аудиторію, її не займають.
 */
export function RoomNowStatus({ schedule, roomId, profile, today, className }: RoomNowStatusProps) {
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

  if (!todayRow || !status || !(status.kind === 'in-pair' || status.kind === 'break' || status.kind === 'before-day')) return null;
  const pair = status.kind === 'in-pair' ? status.current.pair : status.next.pair;
  const busyNow = status.kind === 'in-pair' && todayRow.pairs.some((p) => p.pair === pair && p.busy);
  const nextBusy = todayRow.pairs.find((p) => p.busy && p.pair >= (status.kind === 'in-pair' ? pair + 1 : pair));
  const text = busyNow
    ? 'Зараз іде заняття'
    : nextBusy
      ? `Зараз вільна, наступне заняття — ${nextBusy.pair} пара`
      : 'Зараз вільна до кінця дня';

  return (
    // кольорова крапка сама відділяє речення від парності, як у «1 заміна цього тижня»: окремий « · » зайвий
    <span className={cn('inline-flex items-center gap-2', className)}>
      <StatusDot tone={busyNow ? 'live' : 'neutral'} label={busyNow ? 'Зайнята' : 'Вільна'} />
      {text}
    </span>
  );
}
