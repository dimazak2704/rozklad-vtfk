import { usePublicMeta } from '@/api/meta';
import type { IsoDate, WeekParity } from '@/api/types';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { diffDays, startOfWeek } from '@/lib/date';
import { DateNav } from '../ui/DateNav';
import { ParityBadge } from '../ui/ParityBadge';

/**
 * Парність і номер тижня для робочої дати від сьогоднішніх з /public/meta: тижні чергуються.
 * Далі ніж на два тижні не рахуємо — канікули могли зсунути нумерацію.
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
 * Парність робочої дати — тим самим рядком, що й у публічній смузі: «Знаменник · 10-й тиждень», без плашки.
 * У неділю — знаменник, а в понеділок, на який дивиться диспетчер, — чисельник. На вужчих екранах — лише слово.
 */
function WorkingParity({ date }: { date: IsoDate }) {
  const meta = usePublicMeta();
  if (!meta.data) return null;
  const info = parityFor(date, meta.data.today, meta.data.parity, meta.data.weekNumber);
  if (!info) return null;
  return (
    <span className="hidden shrink-0 items-center gap-2 sm:inline-flex">
      <span aria-hidden="true" className="h-5 w-px bg-[var(--border-color-separator)]" />
      <ParityBadge parity={info.parity} weekNumber={info.week} size="sm" className="hidden xl:inline-flex" />
      <ParityBadge parity={info.parity} size="sm" className="xl:hidden" />
    </span>
  );
}

/**
 * Перемикач робочої дати диспетчера — один контрол замість двох: ‹ [Сьогодні · чт, 12 листопада] ›.
 * Стрілки перескакують вихідні (з пʼятниці › веде в понеділок), мітка відкриває календар, а кнопка
 * «Сьогодні» зʼявляється лише тоді, коли вибрано інший день. Дата живе в `?date=` — посиланням можна
 * поділитися з колегою.
 */
export function WorkingDateSwitcher() {
  const { date, today, setDate } = useWorkingDate();

  return (
    <div className="flex min-w-0 items-center gap-2 lg:gap-3">
      <DateNav value={date} onChange={(value) => setDate(value === today ? null : value)} today={today} size="sm" hotkeys />
      <WorkingParity date={date} />
    </div>
  );
}
