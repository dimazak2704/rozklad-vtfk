import type { CalendarWeek } from '@/api/calendar';
import type { IsoDate } from '@/api/types';
import { compareIsoDates, formatDateRange } from '@/lib/date';
import { PARITY_LABEL } from '@/lib/labels';
import type { ConfirmOptions } from '@/stores/confirm';

/**
 * Підтвердження зміни парності тижня: що станеться з розкладом і як повернути. Календар перебудовує розклад
 * від сьогодні (DOMAIN, «Dated lessons keep following the calendar»), тож минулі дні тижня лишаються як були.
 */
export function parityConfirmation(
  week: Pick<CalendarWeek, 'weekNumber' | 'weekStart' | 'weekEnd' | 'parity' | 'computedParity'>,
  today: IsoDate,
): ConfirmOptions {
  const next = week.parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
  const back = next === week.computedParity;
  const dates = formatDateRange(week.weekStart, week.weekEnd);
  const started = compareIsoDates(today, week.weekStart) > 0 && compareIsoDates(today, week.weekEnd) <= 0;
  // минулі дні розклад не перебудовує: для тижня, що вже минув, міняється лише позначка в календарі
  const past = compareIsoDates(today, week.weekEnd) > 0;
  const head = back
    ? `Тиждень ${dates} знову буде ${PARITY_LABEL[next]}ом, як за чергуванням.`
    : `Тиждень ${dates} за чергуванням — ${PARITY_LABEL[week.computedParity]}, а стане ${PARITY_LABEL[next]}ом.`;
  const effect = past
    ? 'Тиждень уже минув, тож розклад цих днів не зміниться — лише позначка тижня в календарі.'
    : `Розклад усіх груп на цей тиждень перебудується: пари «${PARITY_LABEL[week.parity]}» зникнуть, а «${PARITY_LABEL[next]}» зʼявляться — студенти й викладачі побачать це одразу.`;
  return {
    title: back ? `Повернути ${week.weekNumber}-й тиждень до чергування?` : `Зробити ${week.weekNumber}-й тиждень ${PARITY_LABEL[next]}ом?`,
    description: [head, effect, started ? 'Дні, що вже минули, не зміняться.' : null, 'Повернути можна з журналу дій (Ctrl+Z).']
      .filter(Boolean)
      .join(' '),
    confirmLabel: back ? 'Повернути до чергування' : `Зробити ${PARITY_LABEL[next]}ом`,
    cancelLabel: 'Не змінювати',
  };
}

/** Назва дії меню: «Зробити знаменником…» або, для зміненого вручну, «Повернути до чергування (чисельник)…». */
export function parityActionLabel(week: Pick<CalendarWeek, 'parity' | 'computedParity'>): string {
  const next = week.parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
  return next === week.computedParity ? `Повернути до чергування (${PARITY_LABEL[next]})…` : `Зробити ${PARITY_LABEL[next]}ом…`;
}
