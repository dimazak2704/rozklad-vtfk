import { useQueries } from '@tanstack/react-query';
import { useMemo } from 'react';
import { api } from '@/api/client';
import { useMyChanges } from '@/api/me';
import { qk } from '@/api/queryKeys';
import type { BellSlotDto, BellsDto, DayDto, IsoDate } from '@/api/types';
import { confirmedAssignments, withAssignments } from './my-day';

/**
 * Мій розклад разом із підтвердженими, але ще не опублікованими замінами (`withAssignments`): ті самі пари, які
 * «зараз» уже називає моїми. Дзвінки — лише для дат, де такі заміни є (той самий ключ кешу, що й `useBells`).
 * Доки мої заміни не завантажились, повертає розклад як є.
 */
export function useAssignedSchedule<T extends { days: DayDto[] }>(schedule: T | undefined): T | undefined {
  return useAssignedScheduleState(schedule).data;
}

/**
 * Те саме, що `useAssignedSchedule`, і ще `pending`: мої заміни чи дзвінки до них ще вантажаться. Тоді пара із
 * заміни або ще не додана, або без часу, і речення про неї («Завтра перша о …») краще не казати передчасно.
 */
export function useAssignedScheduleState<T extends { days: DayDto[] }>(schedule: T | undefined): { data: T | undefined; pending: boolean } {
  const changes = useMyChanges();
  const dates = useMemo(() => {
    if (!schedule || !changes.data) return [];
    return schedule.days.map((day) => day.date).filter((date) => confirmedAssignments(changes.data, date).length > 0);
  }, [schedule, changes.data]);
  const bells = useQueries({
    queries: dates.map((date) => ({
      queryKey: qk.public.bells(date),
      queryFn: ({ signal }: { signal: AbortSignal }) => api.get<BellsDto>('/public/bells', { date }, { signal, auth: false }),
      staleTime: 60 * 60_000,
    })),
  });
  const slots = bells.map((query) => query.data?.slots);
  // ключ — самі слоти: масив запитів useQueries новий на кожен рендер, а пам'ятати треба лише зміну даних
  const slotsKey = JSON.stringify(slots);
  const bellsByDate = useMemo(() => {
    const map = new Map<IsoDate, readonly BellSlotDto[]>();
    const parsed = JSON.parse(slotsKey) as (BellSlotDto[] | null)[];
    dates.forEach((date, index) => {
      const daySlots = parsed[index];
      if (daySlots) map.set(date, daySlots);
    });
    return map;
  }, [dates, slotsKey]);
  const data = useMemo(
    () => (schedule && changes.data ? withAssignments(schedule, changes.data, bellsByDate) : schedule),
    [schedule, changes.data, bellsByDate],
  );
  const pending = changes.isPending || bells.some((query) => query.isPending);
  return { data, pending };
}
