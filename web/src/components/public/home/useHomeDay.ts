import { useBells, usePublicMeta } from '@/api/meta';
import type { IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { formatWeekdayDateAfterNa, isWeekend, relativeDayLabel, shiftStudyDay, todayInKyiv } from '@/lib/date';

export interface HomeDay {
  /** Сьогодні, поки йдуть пари; після останнього дзвінка чи поза навчальними днями — наступний навчальний. */
  date: IsoDate;
  today: IsoDate;
  isToday: boolean;
}

/**
 * День, про який розповідає головна: сьогодні, а після останнього дзвінка, у вихідний чи поза навчальними
 * днями — наступний. Так увечері заміни й день своєї групи — вже на завтра, як і речення героя.
 * Що день навчальний, каже сервер (/public/meta: канікули, межі семестру); без нього — лише вихідні.
 */
export function useHomeDay(): HomeDay {
  const meta = usePublicMeta();
  const today = meta.data?.today ?? todayInKyiv();
  const studyDay = meta.data ? meta.data.studyDay : !isWeekend(today);
  // ті самі дзвінки, що й у героя (запит спільний): після останнього день уже минув
  const bells = useBells(meta.data?.studyDay ? today : null);
  const live = useNow(meta.data?.studyDay ? (bells.data?.slots ?? null) : null, { date: today });
  const dayOver = live.status?.kind === 'after-day';
  // наступний навчальний день знає сервер (свята, канікули); без нього — лише вихідні
  const date = studyDay && !dayOver ? today : (meta.data?.nextStudyDay?.date ?? shiftStudyDay(today, 1));
  return { date, today, isToday: date === today };
}

/**
 * Як назвати день головної в реченні: «сьогодні», «завтра» чи «на пʼятницю, 13 листопада».
 * `onDay` — з прийменником для «розпорядження …», `when` — обставина часу («4КІ1 завтра»);
 * `dated` — у назві є число, тож посеред речення після неї ставимо кому.
 */
export function homeDayWords(home: HomeDay): { when: string; onDay: string; dated: boolean } {
  const relative = relativeDayLabel(home.date, home.today);
  if (relative === 'Сьогодні') return { when: 'сьогодні', onDay: 'на сьогодні', dated: false };
  if (relative === 'Завтра') return { when: 'завтра', onDay: 'на завтра', dated: false };
  const onDay = `на ${formatWeekdayDateAfterNa(home.date)}`;
  return { when: onDay, onDay, dated: true };
}
