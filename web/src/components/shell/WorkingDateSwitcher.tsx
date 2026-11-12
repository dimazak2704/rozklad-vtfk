import { useSemesterWeeks } from '@/api/calendar';
import { usePublicMeta } from '@/api/meta';
import type { IsoDate } from '@/api/types';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { DateNav } from '../ui/DateNav';
import { ParityBadge } from '../ui/ParityBadge';
import { workingParity } from './working-parity';

/**
 * Парність робочої дати — тим самим рядком, що й у публічній смузі: «Знаменник · 10-й тиждень», без плашки.
 * У неділю — знаменник, а в понеділок, на який дивиться диспетчер, — чисельник. На вужчих екранах — лише слово.
 */
function WorkingParity({ date }: { date: IsoDate }) {
  const meta = usePublicMeta();
  const semester = meta.data?.semester ?? null;
  const weeks = useSemesterWeeks(semester?.id);
  if (!meta.data) return null;
  const info = workingParity(date, meta.data, semester, weeks.data);
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
