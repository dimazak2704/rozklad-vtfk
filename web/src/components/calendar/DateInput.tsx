import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { useRef, useState, type KeyboardEvent } from 'react';
import { IconButton, Input, Popover, PopoverContent, PopoverTrigger } from '@/components/ui';
import { cn } from '@/lib/cn';
import { addDays, isIsoDate, isoWeekday, parseIsoDate, todayInKyiv, toIsoDate } from '@/lib/date';
import { MONTHS_NOMINATIVE } from '@/lib/labels';
import { isoToText, textToIso } from './dateText';

interface DateInputProps {
  /** ISO-дата або порожньо. */
  value: string;
  /** ISO, якщо набране — дата; інакше сирий текст (тоді схему форми покаже помилку). */
  onChange: (value: string) => void;
  onBlur?: () => void;
  min?: string;
  max?: string;
  name?: string;
  disabled?: boolean;
  autoFocus?: boolean;
  'aria-label'?: string;
  className?: string;
  /** Атрибути для пошуку поля в діалозі (фокус при відкритті). */
  'data-publish-focus'?: string;
}

const WEEK = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'] as const;

/**
 * Дата «11.11.2026» незалежно від мови браузера: текстом з цифровою клавіатурою або з календаря поруч.
 * Тиждень починається з понеділка, як у коледжі; дні поза `min`–`max` вимкнені.
 */
export function DateInput({ value, onChange, onBlur, min, max, className, ...rest }: DateInputProps) {
  const [draft, setDraft] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const shown = draft ?? isoToText(value);

  const commit = () => {
    if (draft === null) return;
    onChange(draft.trim() === '' ? '' : (textToIso(draft) ?? draft));
    setDraft(null);
  };

  return (
    <Input
      type="text"
      inputMode="numeric"
      autoComplete="off"
      placeholder="дд.мм.рррр"
      maxLength={10}
      name={rest.name}
      disabled={rest.disabled}
      autoFocus={rest.autoFocus}
      aria-label={rest['aria-label']}
      data-publish-focus={rest['data-publish-focus']}
      value={shown}
      onChange={(event) => {
        const next = event.target.value.replace(/[^\d./\s-]/g, '');
        setDraft(next);
        // повна дата («11.11.2026») одразу оновлює форму: перевірки поруч (день тижня, межі) бачать її без виходу з поля
        const iso = next.length >= 8 ? textToIso(next) : null;
        if (iso && /\d{4}/.test(next)) onChange(iso);
      }}
      onBlur={() => {
        commit();
        onBlur?.();
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter') commit();
      }}
      className={cn('figures', className)}
      endSlot={
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <IconButton label="Вибрати в календарі" size="sm" disabled={rest.disabled} icon={<CalendarDays aria-hidden="true" className="size-4" />} />
          </PopoverTrigger>
          <PopoverContent width="auto" align="end">
            <MonthGrid
              value={isIsoDate(value) ? value : null}
              min={min}
              max={max}
              onPick={(iso) => {
                setDraft(null);
                onChange(iso);
                setOpen(false);
              }}
            />
          </PopoverContent>
        </Popover>
      }
    />
  );
}

/** Місяць сіткою з понеділка; стрілки рухають вибір, PageUp/PageDown — місяць. */
function MonthGrid({ value, min, max, onPick }: { value: string | null; min?: string; max?: string; onPick: (iso: string) => void }) {
  const today = todayInKyiv();
  const [focus, setFocus] = useState<string>(() => clamp(value ?? today, min, max));
  const gridRef = useRef<HTMLDivElement>(null);
  const { year, month } = parseIsoDate(focus);
  const first = toIsoDate(year, month, 1);
  const start = addDays(first, 1 - isoWeekday(first));
  const days = Array.from({ length: 42 }, (_, index) => addDays(start, index));
  const weeks = days[35] && parseIsoDate(days[35]).month === month ? 6 : 5;
  const outOfRange = (iso: string) => Boolean((min && iso < min) || (max && iso > max));

  const move = (next: string) => {
    const target = clamp(next, min, max);
    setFocus(target);
    requestAnimationFrame(() => gridRef.current?.querySelector<HTMLButtonElement>(`[data-iso="${target}"]`)?.focus());
  };
  const shiftMonth = (delta: number) => {
    const index = year * 12 + (month - 1) + delta;
    const y = Math.floor(index / 12);
    const m = (index % 12) + 1;
    const lastDay = new Date(Date.UTC(y, m, 0)).getUTCDate();
    return toIsoDate(y, m, Math.min(parseIsoDate(focus).day, lastDay));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step: Record<string, () => string> = {
      ArrowLeft: () => addDays(focus, -1),
      ArrowRight: () => addDays(focus, 1),
      ArrowUp: () => addDays(focus, -7),
      ArrowDown: () => addDays(focus, 7),
      PageUp: () => shiftMonth(-1),
      PageDown: () => shiftMonth(1),
      Home: () => addDays(focus, 1 - isoWeekday(focus)),
      End: () => addDays(focus, 7 - isoWeekday(focus)),
    };
    const next = step[event.key];
    if (!next) return;
    event.preventDefault();
    move(next());
  };

  return (
    <div className="flex w-[17.5rem] flex-col gap-2">
      <div className="flex items-center justify-between">
        <IconButton label="Попередній місяць" size="sm" icon={<ChevronLeft aria-hidden="true" className="size-4" />} onClick={() => setFocus(shiftMonth(-1))} />
        <p className="text-ui font-semibold text-primary first-letter:uppercase" aria-live="polite">
          {MONTHS_NOMINATIVE[month - 1]} {year}
        </p>
        <IconButton label="Наступний місяць" size="sm" icon={<ChevronRight aria-hidden="true" className="size-4" />} onClick={() => setFocus(shiftMonth(1))} />
      </div>
      <div ref={gridRef} role="grid" aria-label={`${MONTHS_NOMINATIVE[month - 1]} ${year}`} onKeyDown={onKeyDown} className="grid grid-cols-7 gap-0.5">
        <div role="row" className="contents">
          {WEEK.map((day) => (
            <span key={day} role="columnheader" className="py-1 text-center text-meta font-semibold text-muted">
              {day}
            </span>
          ))}
        </div>
        {Array.from({ length: weeks }, (_, week) => (
          <div key={week} role="row" className="contents">
            {days.slice(week * 7, week * 7 + 7).map((iso) => {
              const { day, month: m } = parseIsoDate(iso);
              const selected = iso === value;
              const disabled = outOfRange(iso);
              return (
                <span key={iso} role="gridcell" aria-selected={selected}>
                  <button
                    type="button"
                    data-iso={iso}
                    tabIndex={iso === focus ? 0 : -1}
                    disabled={disabled}
                    aria-label={`${day} ${MONTHS_NOMINATIVE[m - 1]} ${parseIsoDate(iso).year}`}
                    aria-current={iso === today ? 'date' : undefined}
                    onClick={() => onPick(iso)}
                    className={cn(
                      'flex size-9 w-full items-center justify-center rounded-control text-ui figures pointer-coarse:size-11',
                      'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
                      m === month ? 'text-primary' : 'text-muted',
                      selected ? 'bg-accent font-semibold text-on-accent' : 'hover:bg-hover',
                      iso === today && !selected && 'font-semibold ring-1 ring-[var(--border-color-default)] ring-inset',
                      disabled && 'cursor-not-allowed text-disabled hover:bg-transparent',
                    )}
                  >
                    {day}
                  </button>
                </span>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

function clamp(iso: string, min?: string, max?: string): string {
  if (min && iso < min) return min;
  if (max && iso > max) return max;
  return iso;
}
