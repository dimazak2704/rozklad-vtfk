import { useCallback } from 'react';
import { useSearchParams } from 'react-router';
import type { IsoDate } from '@/api/types';
import { isIsoDate, todayInKyiv } from '@/lib/date';
import { useWorkingDateStore } from '@/stores/date';

/**
 * Робоча дата екрана. Пріоритет: `?date=` в адресі (посилання можна переслати) → вибір у цій
 * вкладці → сьогодні за Києвом. Зміна оновлює і адресу, і памʼять вкладки.
 */
export function useWorkingDate(): {
  date: IsoDate;
  today: IsoDate;
  isToday: boolean;
  setDate: (date: IsoDate | null) => void;
} {
  const [params, setParams] = useSearchParams();
  const stored = useWorkingDateStore((state) => state.date);
  const store = useWorkingDateStore((state) => state.setDate);
  const today = todayInKyiv();
  const fromUrl = params.get('date');
  const date = isIsoDate(fromUrl) ? fromUrl : (stored ?? today);

  const setDate = useCallback(
    (next: IsoDate | null) => {
      const value = next === null || next === today ? null : next;
      store(value);
      setParams(
        (prev) => {
          const copy = new URLSearchParams(prev);
          if (value) copy.set('date', value);
          else copy.delete('date');
          return copy;
        },
        { replace: true },
      );
    },
    [setParams, store, today],
  );

  return { date, today, isToday: date === today, setDate };
}
