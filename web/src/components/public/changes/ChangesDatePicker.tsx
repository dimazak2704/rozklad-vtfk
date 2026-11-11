import { CalendarDays } from 'lucide-react';
import { useState } from 'react';
import { DayPicker } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import type { IsoDate } from '@/api/types';
import { IconButton, Popover, PopoverContent, PopoverTrigger } from '@/components/ui';
import { isoFromLocalDate, isWeekend, localDateFromIso, nearestStudyDay } from '@/lib/date';
import { DAY_PICKER_CLASSES } from '../schedule/day-picker-classes';

interface ChangesDatePickerProps {
  value: IsoDate;
  today: IsoDate;
  onChange: (date: IsoDate) => void;
}

/**
 * Інша дата для «Замін» — значок календаря поруч із «Сьогодні / Завтра». Сама дата написана один раз,
 * повністю, у рядку під заголовком; другого перемикача з тією самою датою немає.
 */
export function ChangesDatePicker({ value, today, onChange }: ChangesDatePickerProps) {
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
          required
          selected={localDateFromIso(value)}
          defaultMonth={localDateFromIso(value)}
          onSelect={(date: Date | undefined) => {
            if (!date) return;
            onChange(nearestStudyDay(isoFromLocalDate(date)));
            setOpen(false);
          }}
          disabled={(date: Date) => isWeekend(isoFromLocalDate(date))}
          modifiers={{ college_today: localDateFromIso(today) }}
          modifiersClassNames={{
            college_today: '[&>button]:font-bold [&>button]:text-accent [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
          }}
          showOutsideDays
          classNames={DAY_PICKER_CLASSES}
        />
      </PopoverContent>
    </Popover>
  );
}
