import { ToggleGroup } from 'radix-ui';
import type { DayDto, IsoDate } from '@/api/types';
import { cn } from '@/lib/cn';
import { addDays, formatWeekdayDate, isoWeekday, parseIsoDate } from '@/lib/date';
import { countLabel, PAIR_FORMS, weekdayShort } from '@/lib/labels';
import { isChangedLesson } from '@/lib/schedule';

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
 * Дні тижня в поданні «День»: пн–пт з числом, кількістю пар і бурштиновою позначкою, якщо в день є зміни.
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
      className={cn('grid grid-cols-5 gap-1 rounded-panel border border-default bg-surface p-1', className)}
    >
      {dates.map((date) => {
        const day = days?.find((d) => d.date === date);
        const count = day?.studyDay ? new Set(day.lessons.filter((l) => l.kind !== 'CANCELLED').map((l) => l.pair)).size : null;
        const changes = day?.lessons.filter(isChangedLesson).length ?? 0;
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
              'group relative flex min-w-0 flex-col items-center gap-0.5 rounded-control px-1 pt-1.5 pb-2 text-center',
              'transition-[background-color,color] duration-100 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
              'hover:bg-hover data-[state=on]:bg-selected data-[state=on]:hover:bg-selected-hover',
            )}
          >
            <span
              className={cn(
                'text-meta font-semibold',
                isToday ? 'text-accent' : 'text-secondary group-data-[state=on]:text-primary',
              )}
            >
              {weekdayShort(isoWeekday(date))}
            </span>
            <span
              className={cn(
                'font-condensed text-heading leading-none font-bold figures',
                studyDay ? 'text-primary group-data-[state=on]:text-accent' : 'text-disabled',
              )}
            >
              {weekday.day}
            </span>
            <span className="flex h-3.5 items-center gap-1 text-meta font-normal text-muted figures" aria-hidden="true">
              {studyDay ? (count === null ? '' : count) : '—'}
              {changes > 0 ? <span className="size-1.5 rounded-full bg-change" /> : null}
            </span>
            {isToday ? (
              <span aria-hidden="true" className="absolute inset-x-3 bottom-0.5 h-0.5 rounded-full bg-accent" />
            ) : null}
          </ToggleGroup.Item>
        );
      })}
    </ToggleGroup.Root>
  );
}
