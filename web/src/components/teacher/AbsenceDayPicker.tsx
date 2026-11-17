import { useState } from 'react';
import { DayPicker } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import type { IsoDate } from '@/api/types';
import { compareIsoDates, isIsoDate, isoFromLocalDate, isWeekend, localDateFromIso } from '@/lib/date';
import { DAY_PICKER_CLASSES } from '../public/schedule/day-picker-classes';
import { nextDaySelection, selectionSummary, type DaySelection } from './absence-days';

/** Календар відсутностей: на дотику дні й стрілки — ціль 44 px. */
const CALENDAR_CLASSES = {
  ...DAY_PICKER_CLASSES,
  selected: '',
  month_caption: 'flex h-9 items-center justify-center font-[650] capitalize pointer-coarse:h-11',
  nav: 'absolute inset-x-0 top-0 flex h-9 items-center justify-between pointer-coarse:h-11',
  button_previous: `${DAY_PICKER_CLASSES.button_previous} pointer-coarse:size-11`,
  button_next: `${DAY_PICKER_CLASSES.button_next} pointer-coarse:size-11`,
  weekday: `${DAY_PICKER_CLASSES.weekday} pointer-coarse:w-11`,
  day_button: `${DAY_PICKER_CLASSES.day_button} pointer-coarse:size-11`,
};

interface AbsenceDayPickerProps {
  value: DaySelection | null;
  onChange: (next: DaySelection) => void;
  /** Сьогодні застосунку (стенду): раніші дні вимкнені — минуле вносить диспетчер (DOMAIN §7). */
  today: IsoDate;
  defaultMonth: IsoDate;
}

/**
 * Діапазон днів у два кліки: перший — початок (одразу підсвічений), другий на пізнішому дні — кінець.
 * Поки кінця немає, наведення (чи фокус клавіатури) показує, яким буде діапазон. Під календарем — що обрано
 * і що робити далі. Вихідні можна взяти в діапазон (п'ятниця–понеділок), але в підсумку рахуємо лише навчальні дні.
 */
export function AbsenceDayPicker({ value, onChange, today, defaultMonth }: AbsenceDayPickerProps) {
  const [pointed, setPointed] = useState<IsoDate | null>(null);
  const valid = value !== null && isIsoDate(value.from) && isIsoDate(value.to);
  // попередній перегляд: від першого дня до дня під курсором (фокусом), лише вперед
  const previewTo = valid && value.awaitingEnd && pointed && compareIsoDates(pointed, value.from) > 0 ? pointed : null;
  const inPreview = (date: Date) => {
    if (!valid || !previewTo) return false;
    const iso = isoFromLocalDate(date);
    return compareIsoDates(iso, value.from) > 0 && compareIsoDates(iso, previewTo) < 0;
  };
  const summary = valid ? selectionSummary(value.from, value.to) : null;

  return (
    <>
      <div className="-ms-px self-start rounded-popover focus-visible:outline-2 focus-visible:outline-focus" data-autofocus tabIndex={-1}>
        <DayPicker
          mode="range"
          locale={uk}
          // «Сьогодні» в календарі (і в підписі дня для читачів екрана) — за годинником застосунку, як і решта
          today={localDateFromIso(today)}
          // один день — теж діапазон from = to: так перший день одразу підсвічений як початок
          selected={valid ? { from: localDateFromIso(value.from), to: localDateFromIso(value.to) } : undefined}
          defaultMonth={localDateFromIso(defaultMonth)}
          // діапазон рахуємо самі (nextDaySelection): бібліотечний addToRange знімає вибір повторним кліком
          // і розвертає діапазон, коли клікнути раніше за початок
          onSelect={(_range, day) => {
            setPointed(null);
            onChange(nextDaySelection(value, isoFromLocalDate(day)));
          }}
          onDayMouseEnter={(day) => setPointed(isoFromLocalDate(day))}
          onDayMouseLeave={() => setPointed(null)}
          onDayFocus={(day) => setPointed(isoFromLocalDate(day))}
          onDayBlur={() => setPointed(null)}
          disabled={{ before: localDateFromIso(today) }}
          modifiers={{
            weekend: (date: Date) => isWeekend(isoFromLocalDate(date)),
            college_today: localDateFromIso(today),
            preview: inPreview,
            preview_end: previewTo ? localDateFromIso(previewTo) : false,
          }}
          modifiersClassNames={{
            weekend: 'text-muted',
            college_today: '[&>button]:font-bold [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
            preview: '[&>button]:rounded-none [&>button]:bg-selected [&>button]:text-accent',
            preview_end: '[&>button]:border [&>button]:border-dashed [&>button]:border-accent [&>button]:bg-selected [&>button]:text-accent',
          }}
          classNames={CALENDAR_CLASSES}
          showOutsideDays
        />
      </div>
      <div role="status" className="flex flex-col gap-0.5">
        <p className="text-ui text-primary figures" data-summary>
          {summary ? (
            <>
              <span className="font-[650]">{summary.when}</span>
              {summary.note ? <span className="text-secondary">, {summary.note}</span> : null}
            </>
          ) : (
            <span className="text-secondary">Дні ще не обрано.</span>
          )}
        </p>
        <p className="text-caption text-muted" data-hint>
          {value?.awaitingEnd ? 'Оберіть останній день, якщо їх кілька.' : summary ? 'Щоб змінити дні, натисніть перший із них.' : 'Натисніть перший день відсутності.'}
        </p>
      </div>
    </>
  );
}
