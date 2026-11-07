import { useWorkingDate } from '@/hooks/useWorkingDate';
import { diffDays, isoWeekday, shiftStudyDay } from '@/lib/date';
import { capitalize, weekdayName } from '@/lib/labels';
import { DateNav } from '../ui/DateNav';
import { SegmentedControl } from '../ui/SegmentedControl';

type Quick = 'today' | 'next' | 'other';

/**
 * Перемикач робочої дати диспетчера: «Сьогодні / Завтра» і будь-яка дата з календаря.
 * У пʼятницю й на вихідних друга кнопка — найближчий навчальний день («Понеділок»).
 * Дата живе в `?date=` — посиланням можна поділитися з колегою.
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
        size="sm"
        value={quick}
        onValueChange={(value) => {
          if (value === 'today') setDate(null);
          if (value === 'next') setDate(next);
        }}
        options={[
          { value: 'today', label: 'Сьогодні' },
          { value: 'next', label: nextLabel },
          ...(quick === 'other' ? [{ value: 'other' as const, label: 'Інша дата' }] : []),
        ]}
        className="hidden shrink-0 xl:inline-flex"
      />
      <DateNav value={date} onChange={(value) => setDate(value)} today={today} size="sm" hotkeys />
    </div>
  );
}
