import { useSyncExternalStore } from 'react';
import { usePublicMeta } from '@/api/meta';
import type { IsoDate, WeekParity } from '@/api/types';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { diffDays, isoWeekday, shiftStudyDay, startOfWeek } from '@/lib/date';
import { capitalize, weekdayName } from '@/lib/labels';
import { DateNav } from '../ui/DateNav';
import { ParityBadge } from '../ui/ParityBadge';
import { SegmentedControl } from '../ui/SegmentedControl';

type Quick = 'today' | 'next' | 'other';

/** ≥1024 px: поруч із датою стоїть «Сьогодні | Завтра», тож сама мітка дати — без відносного слова. */
const WIDE = '(min-width: 1024px)';
function subscribeWide(onChange: () => void): () => void {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const list = window.matchMedia(WIDE);
  list.addEventListener?.('change', onChange);
  return () => list.removeEventListener?.('change', onChange);
}
function useWide(): boolean {
  return useSyncExternalStore(
    subscribeWide,
    () => typeof window !== 'undefined' && Boolean(window.matchMedia?.(WIDE).matches),
    () => true,
  );
}

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
 * Перемикач робочої дати диспетчера: «Сьогодні | Завтра» і будь-яка дата з календаря.
 * У пʼятницю й на вихідних друга кнопка — найближчий навчальний день («Понеділок»).
 * Для іншої дати жоден сегмент не вибраний. Дата живе в `?date=` — посиланням можна поділитися з колегою.
 */
export function WorkingDateSwitcher() {
  const { date, today, setDate } = useWorkingDate();
  const next = shiftStudyDay(today, 1);
  const nextLabel = diffDays(today, next) === 1 ? 'Завтра' : capitalize(weekdayName(isoWeekday(next)));
  const quick: Quick = date === today ? 'today' : date === next ? 'next' : 'other';
  const wide = useWide();

  return (
    <div className="flex min-w-0 items-center gap-2 lg:gap-3">
      <SegmentedControl<Quick>
        aria-label="Робоча дата"
        value={quick}
        onValueChange={(value) => {
          if (value === 'today') setDate(null);
          if (value === 'next') setDate(next);
        }}
        options={[
          { value: 'today', label: 'Сьогодні' },
          { value: 'next', label: nextLabel },
        ]}
        className="hidden shrink-0 lg:inline-flex"
      />
      {/*
        «Сьогодні» — лише одним словом на екрані: на широкому його несе перемикач, а мітка між стрілками —
        абсолютна дата «нд, 8 листопада»; на вужчому перемикача немає, тож мітка відносна, а для іншої дати
        зʼявляється кнопка «Сьогодні».
      */}
      <DateNav
        value={date}
        onChange={(value) => setDate(value)}
        today={today}
        size="sm"
        hotkeys
        showRelative={!wide}
        showTodayButton={!wide}
      />
      <WorkingParity date={date} />
    </div>
  );
}
