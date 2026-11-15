import type { AbsenceDto } from '@/api/types';
import { formatDateRange, formatShortWeekdayDate } from '@/lib/date';
import { ABSENCE_REASON_LABEL } from '@/lib/labels';
import { pairsText } from '../format';

/**
 * «увесь день · лікарняний», а для кількох днів — «11–13 листопада · лікарняний». У поданні «Тиждень» одноденна
 * відсутність каже й день («Пт, 20.11 · увесь день · лікарняний»): той самий викладач буває у списку двічі.
 */
export function absenceCaption(absence: AbsenceDto, withDate = false): string {
  const single = absence.dateFrom === absence.dateTo;
  const when = single ? pairsText(absence.pairs) : formatDateRange(absence.dateFrom, absence.dateTo);
  const day = single && withDate ? `${formatShortWeekdayDate(absence.dateFrom)} · ` : '';
  return `${day}${when} · ${ABSENCE_REASON_LABEL[absence.reasonCategory].toLocaleLowerCase('uk')}`;
}
