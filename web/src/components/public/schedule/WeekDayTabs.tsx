import { ToggleGroup } from 'radix-ui';
import type { DayDto, IsoDate } from '@/api/types';
import { cn } from '@/lib/cn';
import { addDays, formatWeekdayDate, isoWeekday, parseIsoDate } from '@/lib/date';
import { countLabel, PAIR_FORMS, weekdayShort } from '@/lib/labels';
import { isChangedLesson, vacatedPairs } from '@/lib/schedule';

interface WeekDayTabsProps {
  /** Понеділок тижня на екрані. */
  weekStart: IsoDate;
  /** Дні з розкладу (якщо вже завантажені) — дають кількість пар і позначку змін. */
  days?: readonly DayDto[];
  value: IsoDate;
  today: IsoDate;
  onChange: (date: IsoDate) => void;
  className?: string;
}

/**
 * Дні тижня в поданні «День»: пн–пт і число; сьогодні — число в акцентному кружку, день зі змінами —
 * маленька бурштинова крапка. Кількість пар — лише для читача екрана (не перевантажуємо цифрами).
 * Стрілки ←/→ усередині перемикають день (roving focus Radix), Enter/пробіл — вибір.
 */
export function WeekDayTabs({ weekStart, days, value, today, onChange, className }: WeekDayTabsProps) {
  const dates = Array.from({ length: 5 }, (_, index) => addDays(weekStart, index));

  return (
    <ToggleGroup.Root
      type="single"
      value={value}
      onValueChange={(next) => {
        if (next) onChange(next);
      }}
      aria-label="День тижня"
      className={cn('grid grid-cols-5 gap-1', className)}
    >
      {dates.map((date) => {
        const day = days?.find((d) => d.date === date);
        const count = day?.studyDay ? new Set(day.lessons.filter((l) => l.kind !== 'CANCELLED').map((l) => l.pair)).size : null;
        // пара, звільнена перенесенням, — теж зміна дня (у розпорядженні це «Пари не буде»)
        const changes = day ? day.lessons.filter(isChangedLesson).length + vacatedPairs(day.lessons).size : 0;
        const isToday = date === today;
        const studyDay = day ? day.studyDay : true;
        const weekday = parseIsoDate(date);
        const description = [
          formatWeekdayDate(date),
          isToday ? 'сьогодні' : null,
          !studyDay ? 'не навчальний день' : count !== null ? countLabel(count, PAIR_FORMS) : null,
          changes > 0 ? `змін: ${changes}` : null,
        ]
          .filter(Boolean)
          .join(', ');
        return (
          <ToggleGroup.Item
            key={date}
            value={date}
            aria-label={description}
            className={cn(
              'group relative flex min-h-16 min-w-0 flex-col items-center justify-center gap-1 rounded-control px-1 py-2 text-center',
              'transition-[background-color,color] duration-150 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
              'hover:bg-hover data-[state=on]:bg-selected data-[state=on]:hover:bg-selected',
            )}
          >
            <span className={cn('relative text-meta font-semibold', isToday ? 'text-accent' : 'text-secondary group-data-[state=on]:text-primary')}>
              {weekdayShort(isoWeekday(date))}
              {changes > 0 ? <span aria-hidden="true" className="absolute top-0.5 -end-2.5 size-1.5 rounded-full bg-change" /> : null}
            </span>
            <span
              className={cn(
                'inline-flex h-7 min-w-7 items-center justify-center rounded-full px-1.5 font-display text-ui font-semibold figures',
                isToday ? 'bg-accent text-on-accent' : studyDay ? 'text-primary' : 'text-muted',
              )}
            >
              {weekday.day}
            </span>
          </ToggleGroup.Item>
        );
      })}
    </ToggleGroup.Root>
  );
}
