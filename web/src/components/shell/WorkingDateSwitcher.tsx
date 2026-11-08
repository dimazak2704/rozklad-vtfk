import { usePublicMeta } from '@/api/meta';
import type { IsoDate, WeekParity } from '@/api/types';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { diffDays, isoWeekday, shiftStudyDay, startOfWeek } from '@/lib/date';
import { capitalize, PARITY_LABEL, weekdayName } from '@/lib/labels';
import { weekOrdinalShort } from '@/lib/ordinal';
import { DateNav } from '../ui/DateNav';
import { ParityMark } from '../ui/ParityBadge';
import { SegmentedControl } from '../ui/SegmentedControl';

type Quick = 'today' | 'next' | 'other';

/**
 * Парність і номер тижня для робочої дати від сьогоднішніх з /public/meta: тижні чергуються.
 * Далі ніж на два тижні не рахуємо — канікули могли зсунути нумерацію.
 */
function parityFor(date: IsoDate, today: IsoDate, parity: WeekParity, weekNumber: number): { parity: WeekParity; week: number } | null {
  const weeks = Math.round(diffDays(startOfWeek(today), startOfWeek(date)) / 7);
  if (Math.abs(weeks) > 2) return null;
  const flipped = parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
  return { parity: weeks % 2 === 0 ? parity : flipped, week: weekNumber + weeks };
}

/** Табличка парності робочої дати: у неділю — знаменник, а в понеділок, на який дивиться диспетчер, — чисельник. */
function WorkingParity({ date }: { date: IsoDate }) {
  const meta = usePublicMeta();
  if (!meta.data) return null;
  const info = parityFor(date, meta.data.today, meta.data.parity, meta.data.weekNumber);
  if (!info) return null;
  return (
    <span className="hidden h-6 shrink-0 items-center gap-1.5 rounded-plate bg-sunken px-2 text-meta font-medium whitespace-nowrap text-secondary figures sm:inline-flex">
      <ParityMark parity={info.parity} className="text-accent" />
      {PARITY_LABEL[info.parity]} · {weekOrdinalShort(info.week)}
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

  return (
    <div className="flex min-w-0 items-center gap-2">
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
      {/* на lg+ відносний день уже видно в перемикачі, тож у кнопці дати — лише «нд, 8 листопада» */}
      <DateNav
        value={date}
        onChange={(value) => setDate(value)}
        today={today}
        size="sm"
        hotkeys
        showTodayButton={false}
        className="lg:hidden"
      />
      <DateNav
        value={date}
        onChange={(value) => setDate(value)}
        today={today}
        size="sm"
        showTodayButton={false}
        showRelative={false}
        className="hidden lg:inline-flex"
      />
      <WorkingParity date={date} />
    </div>
  );
}
