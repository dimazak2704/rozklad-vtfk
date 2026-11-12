import type { IsoDate, PublicMetaDto, SemesterRef, SemesterWeekDto, WeekParity } from '@/api/types';
import { compareIsoDates, diffDays, startOfWeek } from '@/lib/date';

/**
 * Запасний шлях, поки тижні семестру ще не завантажилися (чи недоступні): від сьогоднішніх парності й
 * номера з /public/meta тижні чергуються. Далі ніж на два тижні не рахуємо — канікули могли зсунути нумерацію.
 */
function parityFor(
  date: IsoDate,
  today: IsoDate,
  parity: WeekParity | null,
  weekNumber: number | null,
): { parity: WeekParity; week: number } | null {
  // сьогодні поза семестром — від чого відраховувати, невідомо; парність не вигадуємо
  if (!parity || weekNumber === null) return null;
  const weeks = Math.round(diffDays(startOfWeek(today), startOfWeek(date)) / 7);
  if (Math.abs(weeks) > 2) return null;
  const flipped = parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
  return { parity: weeks % 2 === 0 ? parity : flipped, week: weekNumber + weeks };
}

/**
 * Парність і номер тижня робочої дати. Правда — тиждень семестру з GET /admin/semesters/{id}/weeks:
 * там уже враховано ручне перевизначення (DOMAIN §2: `week_parity_override` виграє), тож смуга
 * збігається з шапкою розпорядження з сервера. Дата поза семестром парності не має.
 * Чергування від сьогодні — лише поки тижнів ще немає (вантажаться чи збій).
 */
export function workingParity(
  date: IsoDate,
  meta: Pick<PublicMetaDto, 'today' | 'parity' | 'weekNumber'>,
  semester: Pick<SemesterRef, 'startsOn' | 'endsOn'> | null,
  weeks: readonly SemesterWeekDto[] | undefined,
): { parity: WeekParity; week: number } | null {
  if (semester && (compareIsoDates(date, semester.startsOn) < 0 || compareIsoDates(date, semester.endsOn) > 0)) return null;
  if (weeks) {
    const week = weeks.find((w) => w.weekStart === startOfWeek(date));
    if (week) return { parity: week.parity, week: week.weekNumber };
  }
  return parityFor(date, meta.today, meta.parity, meta.weekNumber);
}
