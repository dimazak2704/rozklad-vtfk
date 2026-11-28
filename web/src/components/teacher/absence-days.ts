import type { IsoDate } from '@/api/types';
import { addDays, compareIsoDates, formatDateRange, formatWeekdayDate, isWeekend } from '@/lib/date';
import { countLabel } from '@/lib/labels';

/**
 * Обрані дні відсутності. `awaitingEnd` — обрано лише перший день, наступний клік на пізнішому дні його завершить.
 * Поки кінця немає, діапазон — один день: форму можна надіслати й так.
 */
export interface DaySelection {
  from: IsoDate;
  to: IsoDate;
  awaitingEnd: boolean;
}

/**
 * Що робить клік на день `day` (передбачувано, без «через раз»):
 * - кінця не чекаємо (нічого не обрано, обрано день чи діапазон) — це новий перший день;
 * - чекаємо кінця: пізніший день — кінець; той самий — лишається один день; раніший — новий перший день.
 */
export function nextDaySelection(current: DaySelection | null, day: IsoDate): DaySelection {
  if (!current?.awaitingEnd) return { from: day, to: day, awaitingEnd: true };
  const order = compareIsoDates(day, current.from);
  if (order < 0) return { from: day, to: day, awaitingEnd: true };
  return { from: current.from, to: day, awaitingEnd: false };
}

const STUDY_DAY_FORMS = ['навчальний день', 'навчальні дні', 'навчальних днів'] as const;

export function studyDaysBetween(from: IsoDate, to: IsoDate): number {
  let count = 0;
  for (let cursor = from; compareIsoDates(cursor, to) <= 0; cursor = addDays(cursor, 1)) if (!isWeekend(cursor)) count += 1;
  return count;
}

/** «Пʼятниця, 20 листопада»; «20–24 листопада» + «3 навчальні дні»; вихідні так і називаємо. */
export function selectionSummary(from: IsoDate, to: IsoDate): { when: string; note: string | null } {
  if (from === to) return { when: formatWeekdayDate(from), note: isWeekend(from) ? 'вихідний' : null };
  const study = studyDaysBetween(from, to);
  return { when: formatDateRange(from, to), note: study === 0 ? 'навчальних днів немає' : countLabel(study, STUDY_DAY_FORMS) };
}
