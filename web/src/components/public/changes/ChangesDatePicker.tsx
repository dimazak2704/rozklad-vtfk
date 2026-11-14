import { CalendarDays } from 'lucide-react';
import { useState } from 'react';
import { DayPicker } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import type { IsoDate } from '@/api/types';
import { IconButton, Popover, PopoverContent, PopoverTrigger } from '@/components/ui';
import { compareIsoDates, isoFromLocalDate, isWeekend, localDateFromIso, nearestStudyDay } from '@/lib/date';
import { CHANGES_HISTORY_HINT } from '../history';
import { DAY_PICKER_CLASSES } from '../schedule/day-picker-classes';

interface ChangesDatePickerProps {
  value: IsoDate;
  today: IsoDate;
  onChange: (date: IsoDate) => void;
  /** Найраніша дата, яку можна обрати (гість бачить заміни лише з неї); null — без межі (персонал). */
  minDate?: IsoDate | null;
}

/**
 * Інша дата для «Замін» — значок календаря поруч із «Сьогодні / Завтра». Сама дата написана один раз,
 * повністю, у рядку під заголовком; другого перемикача з тією самою датою немає.
 */
export function ChangesDatePicker({ value, today, onChange, minDate = null }: ChangesDatePickerProps) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <IconButton label="Інша дата" icon={<CalendarDays aria-hidden="true" className="size-[1.125rem]" />} />
      </PopoverTrigger>
      <PopoverContent width="auto" align="end" className="p-2">
        <DayPicker
          mode="single"
          locale={uk}
          today={localDateFromIso(today)}
          required
          selected={localDateFromIso(value)}
          defaultMonth={localDateFromIso(value)}
          startMonth={minDate ? localDateFromIso(minDate) : undefined}
          onSelect={(date: Date | undefined) => {
            if (!date) return;
            onChange(nearestStudyDay(isoFromLocalDate(date)));
            setOpen(false);
          }}
          disabled={(date: Date) => {
            const iso = isoFromLocalDate(date);
            return isWeekend(iso) || (minDate !== null && compareIsoDates(iso, minDate) < 0);
          }}
          modifiers={{ college_today: localDateFromIso(today) }}
          modifiersClassNames={{
            college_today: '[&>button]:font-bold [&>button]:text-accent [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
          }}
          showOutsideDays
          classNames={DAY_PICKER_CLASSES}
        />
        {/* ширина — як у календаря: текст переноситься, а не розтягує спливне вікно */}
        {minDate ? <p className="w-0 min-w-full border-t border-separator px-1 pt-2 text-caption text-muted">{CHANGES_HISTORY_HINT}</p> : null}
      </PopoverContent>
    </Popover>
  );
}
