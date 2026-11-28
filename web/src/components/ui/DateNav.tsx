import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { DayPicker } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import type { IsoDate } from '@/api/types';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import { weekdayShort } from '@/lib/labels';
import {
  addDays,
  formatDayMonth,
  formatDayMonthShort,
  isoFromLocalDate,
  isoWeekday,
  isWeekend,
  localDateFromIso,
  relativeDayLabel,
  shiftStudyDay,
  todayInKyiv,
} from '@/lib/date';
import { Button } from './Button';
import { IconButton } from './IconButton';
import { Popover, PopoverContent, PopoverTrigger } from './Popover';

export interface DateNavProps {
  value: IsoDate;
  onChange: (date: IsoDate) => void;
  /** «Сьогодні» за Києвом; можна передати з /public/meta. */
  today?: IsoDate;
  /** Стрілки перескакують вихідні. */
  studyDaysOnly?: boolean;
  min?: IsoDate;
  max?: IsoDate;
  /** ←/→ — попередній/наступний день, T — сьогодні (UI.md §3). Вмикайте лише на одному DateNav на екрані. */
  hotkeys?: boolean;
  tone?: 'default' | 'on-shell';
  size?: 'sm' | 'md';
  /** Кнопка «Сьогодні» поруч, коли вибрано інший день. Вимкніть, якщо поруч уже є «Сьогодні | Завтра». */
  showTodayButton?: boolean;
  /** «Сьогодні, 8 листопада» замість «нд, 8 листопада». Вимкніть, якщо відносний день видно поруч. */
  showRelative?: boolean;
  className?: string;
}

/** Перемикач дня: ‹ [нд, 8 листопада] › з календарем і, за потреби, «Сьогодні». */
export function DateNav({
  value,
  onChange,
  today = todayInKyiv(),
  studyDaysOnly = true,
  min,
  max,
  hotkeys = false,
  tone = 'default',
  size = 'md',
  showTodayButton = true,
  showRelative = true,
  className,
}: DateNavProps) {
  const [open, setOpen] = useState(false);
  const step = (direction: 1 | -1) => (studyDaysOnly ? shiftStudyDay(value, direction) : addDays(value, direction));
  const prev = step(-1);
  const next = step(1);
  const prevDisabled = min !== undefined && prev < min;
  const nextDisabled = max !== undefined && next > max;
  const relative = showRelative ? relativeDayLabel(value, today) : null;
  const weekday = weekdayShort(isoWeekday(value)).toLocaleLowerCase('uk');
  const shortLabel = `${weekday}, ${formatDayMonth(value)}`;
  // найвужчі телефони: «ср, 11 лист.» — дата вміщається цілою і поруч зі станом звʼязку
  const compactLabel = `${weekday}, ${formatDayMonthShort(value)}`;
  const isToday = value === today;

  const go = (date: IsoDate) => {
    if ((min && date < min) || (max && date > max)) return;
    onChange(date);
  };

  useHotkeys([
    { keys: 'arrowleft', handler: () => go(prev), description: 'Попередній день', group: 'Дата', enabled: hotkeys && !prevDisabled },
    { keys: 'arrowright', handler: () => go(next), description: 'Наступний день', group: 'Дата', enabled: hotkeys && !nextDisabled },
    { keys: 't', handler: () => go(today), description: 'Сьогодні', group: 'Дата', enabled: hotkeys },
  ]);

  void tone; // застаріле: колишня синя смуга

  return (
    <div className={cn('inline-flex min-w-0 items-center gap-0.5', className)} role="group" aria-label="Вибір дня">
      <IconButton
        label="Попередній день"
        shortcut={hotkeys ? 'arrowleft' : undefined}
        icon={<ChevronLeft aria-hidden="true" className="size-4 rtl:rotate-180" />}
        size={size}
        disabled={prevDisabled}
        onClick={() => go(prev)}
      />
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="quiet"
            size={size}
            static
            // на вузькій панелі мітка скорочується (truncate), а не виштовхує сусідів за край екрана
            className="min-w-0 shrink px-2 text-primary sm:min-w-[8.25rem]"
          >
            {/* на телефоні — лише дата: саме її диспетчер має бачити завжди, «Сьогодні» підкаже кнопка поруч */}
            <span className="truncate font-[650]">
              {relative ? <span className="hidden sm:inline">{relative} · </span> : null}
              <span className={cn('max-[399px]:hidden', relative && 'sm:font-[450] sm:text-secondary')}>{shortLabel}</span>
              <span className="min-[400px]:hidden">{compactLabel}</span>
            </span>
            {/* назва кнопки — те, що на ній видно на цій ширині (WCAG 2.5.3), і далі — що вона робить */}
            <span className="sr-only">. Обрати дату</span>
          </Button>
        </PopoverTrigger>
        <PopoverContent width="auto" align="center" className="p-2">
          <DayPicker
            mode="single"
            locale={uk}
            today={localDateFromIso(today)}
            required
            selected={localDateFromIso(value)}
            defaultMonth={localDateFromIso(value)}
            onSelect={(date: Date | undefined) => {
              if (!date) return;
              go(isoFromLocalDate(date));
              setOpen(false);
            }}
            disabled={[
              ...(min ? [{ before: localDateFromIso(min) }] : []),
              ...(max ? [{ after: localDateFromIso(max) }] : []),
            ]}
            modifiers={{ weekend: (date: Date) => isWeekend(isoFromLocalDate(date)), college_today: localDateFromIso(today) }}
            modifiersClassNames={{
              weekend: 'text-muted',
              college_today: '[&>button]:font-semibold [&>button]:text-accent [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
            }}
            showOutsideDays
            classNames={{
              root: 'text-ui',
              months: 'relative',
              month_caption: 'flex h-9 items-center justify-center font-semibold capitalize',
              nav: 'absolute inset-x-0 top-0 flex h-9 items-center justify-between',
              button_previous:
                'inline-flex size-8 items-center justify-center rounded-control text-secondary hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-40',
              button_next:
                'inline-flex size-8 items-center justify-center rounded-control text-secondary hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-40',
              chevron: 'size-4 fill-current',
              month_grid: 'mt-1 border-collapse',
              weekdays: '',
              weekday: 'h-8 w-9 text-center text-meta font-semibold text-muted',
              week: '',
              day: 'p-0 text-center',
              day_button:
                'm-px inline-flex size-9 items-center justify-center rounded-control figures hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus',
              selected: '[&>button]:bg-accent [&>button]:text-on-accent [&>button]:hover:bg-accent-hover',
              today: '',
              outside: 'opacity-45',
              disabled: 'opacity-35 [&>button]:cursor-not-allowed [&>button]:hover:bg-transparent',
            }}
          />
          <div className="flex justify-between gap-2 border-t border-separator px-1 pt-2">
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                go(today);
                setOpen(false);
              }}
            >
              Сьогодні
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                go(shiftStudyDay(today, 1));
                setOpen(false);
              }}
            >
              Наступний навчальний день
            </Button>
          </div>
        </PopoverContent>
      </Popover>
      <IconButton
        label="Наступний день"
        shortcut={hotkeys ? 'arrowright' : undefined}
        icon={<ChevronRight aria-hidden="true" className="size-4 rtl:rotate-180" />}
        size={size}
        disabled={nextDisabled}
        onClick={() => go(next)}
      />
      {showTodayButton && !isToday ? (
        <Button variant="ghost" size={size} onClick={() => go(today)} className="hidden sm:inline-flex">
          Сьогодні
        </Button>
      ) : null}
    </div>
  );
}
