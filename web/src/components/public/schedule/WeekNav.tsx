import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { DayPicker } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import type { IsoDate } from '@/api/types';
import { Button, IconButton, Popover, PopoverContent, PopoverTrigger } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import { addDays, formatDateRange, isoFromLocalDate, isWeekend, localDateFromIso, nearestStudyDay } from '@/lib/date';
import { DAY_PICKER_CLASSES } from './day-picker-classes';
import { studyWeek } from './schedule-params';

interface WeekNavProps {
  /** Будь-який день тижня на екрані. */
  value: IsoDate;
  onChange: (date: IsoDate) => void;
  today: IsoDate;
  /** ←/→ — попередній/наступний тиждень, T — цей тиждень. Лише на одному перемикачі на екрані. */
  hotkeys?: boolean;
  className?: string;
}

/** Перемикач тижня: ‹ 9–13 листопада › з календарем; день тижня при гортанні зберігається. */
export function WeekNav({ value, onChange, today, hotkeys = false, className }: WeekNavProps) {
  const [open, setOpen] = useState(false);
  const week = studyWeek(value);
  const home = nearestStudyDay(today);
  const isCurrent = studyWeek(home).from === week.from;

  useHotkeys([
    { keys: 'arrowleft', handler: () => onChange(addDays(value, -7)), description: 'Попередній тиждень', group: 'Розклад', enabled: hotkeys },
    { keys: 'arrowright', handler: () => onChange(addDays(value, 7)), description: 'Наступний тиждень', group: 'Розклад', enabled: hotkeys },
    { keys: 't', handler: () => onChange(home), description: 'Цей тиждень', group: 'Розклад', enabled: hotkeys },
  ]);

  return (
    <div className={cn('inline-flex min-w-0 items-center gap-1', className)} role="group" aria-label="Вибір тижня">
      <IconButton
        label="Попередній тиждень"
        shortcut={hotkeys ? 'arrowleft' : undefined}
        icon={<ChevronLeft aria-hidden="true" className="size-4 rtl:rotate-180" />}
        onClick={() => onChange(addDays(value, -7))}
      />
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="secondary"
            iconStart={<CalendarDays aria-hidden="true" className="size-4" />}
            aria-label={`Обрати тиждень. Зараз: ${formatDateRange(week.from, week.to)}`}
            className="min-w-0 justify-start"
          >
            <span className="truncate figures">
              {isCurrent ? <span className="me-1">Цей тиждень,</span> : null}
              <span className={cn(isCurrent && 'font-normal text-secondary')}>{formatDateRange(week.from, week.to)}</span>
            </span>
          </Button>
        </PopoverTrigger>
        <PopoverContent width="auto" align="center" className="p-2">
          <DayPicker
            mode="single"
            locale={uk}
            required
            selected={localDateFromIso(value)}
            defaultMonth={localDateFromIso(value)}
            onSelect={(date: Date | undefined) => {
              if (!date) return;
              onChange(nearestStudyDay(isoFromLocalDate(date)));
              setOpen(false);
            }}
            modifiers={{
              chosen_week: { from: localDateFromIso(week.from), to: localDateFromIso(week.to) },
              weekend: (date: Date) => isWeekend(isoFromLocalDate(date)),
              college_today: localDateFromIso(today),
            }}
            modifiersClassNames={{
              chosen_week: '[&>button]:bg-selected',
              weekend: 'text-muted',
              college_today: '[&>button]:font-bold [&>button]:text-accent [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
            }}
            showOutsideDays
            classNames={DAY_PICKER_CLASSES}
          />
          <div className="flex justify-between gap-2 border-t border-separator px-1 pt-2">
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                onChange(home);
                setOpen(false);
              }}
            >
              Цей тиждень
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                onChange(addDays(studyWeek(home).from, 7));
                setOpen(false);
              }}
            >
              Наступний тиждень
            </Button>
          </div>
        </PopoverContent>
      </Popover>
      <IconButton
        label="Наступний тиждень"
        shortcut={hotkeys ? 'arrowright' : undefined}
        icon={<ChevronRight aria-hidden="true" className="size-4 rtl:rotate-180" />}
        onClick={() => onChange(addDays(value, 7))}
      />
      {!isCurrent ? (
        <Button variant="ghost" onClick={() => onChange(home)} className="hidden sm:inline-flex">
          Цей тиждень
        </Button>
      ) : null}
    </div>
  );
}
