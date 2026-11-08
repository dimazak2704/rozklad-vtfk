import { usePublicMeta } from '@/api/meta';
import type { IsoDate } from '@/api/types';
import { isWeekend, shiftStudyDay, todayInKyiv } from '@/lib/date';

export interface HomeDay {
  /** Сьогодні, якщо навчальний день, інакше найближчий наступний. */
  date: IsoDate;
  today: IsoDate;
  isToday: boolean;
}

/**
 * День, про який розповідає головна: сьогодні, а у вихідний чи поза навчальними днями — наступний.
 * Що день навчальний, каже сервер (/public/meta: канікули, межі семестру); без нього — лише вихідні.
 */
export function useHomeDay(): HomeDay {
  const meta = usePublicMeta();
  const today = meta.data?.today ?? todayInKyiv();
  const studyDay = meta.data ? meta.data.studyDay : !isWeekend(today);
  // наступний навчальний день знає сервер (свята, канікули); без нього — лише вихідні
  const date = studyDay ? today : (meta.data?.nextStudyDay?.date ?? shiftStudyDay(today, 1));
  return { date, today, isToday: date === today };
}
