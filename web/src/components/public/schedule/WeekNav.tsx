import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useState } from 'react';
import type { IsoDate } from '@/api/types';
import {
  Button,
  IconButton,
  LazyDayPicker,
  notify,
  Popover,
  PopoverContent,
  PopoverTrigger,
  preloadDayPicker,
  Tooltip,
} from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import {
  addDays,
  compareIsoDates,
  formatDateRange,
  formatDayMonthShort,
  isoFromLocalDate,
  isWeekend,
  localDateFromIso,
  nearestStudyDay,
  parseIsoDate,
  startOfWeek,
} from '@/lib/date';
import { SCHEDULE_HISTORY_HINT } from '../history';
import { DAY_PICKER_CLASSES } from './day-picker-classes';
import { studyWeek } from './schedule-params';

interface WeekNavProps {
  /** Будь-який день тижня на екрані. */
  value: IsoDate;
  onChange: (date: IsoDate) => void;
  today: IsoDate;
  /** ←/→ — попередній/наступний тиждень, T — цей тиждень. Лише на одному перемикачі на екрані. */
  hotkeys?: boolean;
  /**
   * Де стоїть «До цього тижня», коли на екрані інший тиждень: `below` — тихим посиланням під датами
   * (у заголовку сторінки, де керування вирівняні праворуч, — щоб перемикач не «зʼїжджав» під курсором),
   * `none` — сторінка показує його сама (телефон).
   */
  returnLink?: 'below' | 'none';
  /** Телефон: «9–13 лист.» замість «9–13 листопада», щоб поруч умістився «День / Тиждень». */
  compact?: boolean;
  /**
   * Найраніша дата, яку бачить гість (`publicHistoryFrom`): гортати можна до понеділка її тижня
   * (розклад віддається цілими тижнями). null — без межі (персонал, кабінет викладача).
   */
  minDate?: IsoDate | null;
  className?: string;
}

/** «9–13 лист.», «30 жовт.–3 лист.»: найкоротший запис тижня, який ще читається без зусиль. */
function shortRange(from: IsoDate, to: IsoDate): string {
  if (parseIsoDate(from).month === parseIsoDate(to).month) return `${parseIsoDate(from).day}–${formatDayMonthShort(to)}`;
  return `${formatDayMonthShort(from)}–${formatDayMonthShort(to)}`;
}

/**
 * Перемикач тижня: ‹ 9–13 листопада › з календарем під датами; день тижня при гортанні зберігається.
 * З межею (`minDate`) «‹» на першому відкритому тижні тьмяніє, але лишається у фокусі з підказкою, а натискання
 * пояснює, чому далі не можна (на дотику підказок немає).
 */
export function WeekNav({ value, onChange, today, hotkeys = false, returnLink = 'below', compact = false, minDate = null, className }: WeekNavProps) {
  const [open, setOpen] = useState(false);
  const week = studyWeek(value);
  const home = nearestStudyDay(today);
  const isCurrent = studyWeek(home).from === week.from;
  const firstWeek = minDate ? startOfWeek(minDate) : null;
  const atFirstWeek = firstWeek !== null && compareIsoDates(week.from, firstWeek) <= 0;
  const previousWeek = () => {
    if (atFirstWeek) notify.info(SCHEDULE_HISTORY_HINT);
    else onChange(addDays(value, -7));
  };

  useHotkeys([
    { keys: 'arrowleft', handler: previousWeek, description: 'Попередній тиждень', group: 'Розклад', enabled: hotkeys },
    { keys: 'arrowright', handler: () => onChange(addDays(value, 7)), description: 'Наступний тиждень', group: 'Розклад', enabled: hotkeys },
    { keys: 't', handler: () => onChange(home), description: 'Цей тиждень', group: 'Розклад', enabled: hotkeys },
  ]);

  return (
    <div className={cn('relative inline-flex min-w-0 items-center gap-1', className)} role="group" aria-label="Вибір тижня">
      <IconButton
        label={atFirstWeek ? 'Попередній тиждень — лише для персоналу коледжу' : 'Попередній тиждень'}
        shortcut={hotkeys && !atFirstWeek ? 'arrowleft' : undefined}
        icon={<ChevronLeft aria-hidden="true" className="size-4 rtl:rotate-180" />}
        aria-disabled={atFirstWeek || undefined}
        className={atFirstWeek ? 'text-disabled hover:bg-transparent hover:text-disabled active:bg-transparent' : undefined}
        onClick={previousWeek}
      />
      <Popover open={open} onOpenChange={setOpen}>
        {/* дати тижня не кажуть, що під ними календар: підказка — як у стрілок поруч */}
        <Tooltip content="Обрати тиждень">
          <PopoverTrigger asChild>
            <Button
              variant="ghost"
              aria-label={`Обрати тиждень. Зараз: ${isCurrent ? 'цей тиждень, ' : ''}${formatDateRange(week.from, week.to)}`}
              className={cn('min-w-0 text-primary', compact ? 'px-1.5' : 'px-2.5')}
              // код календаря — окремою частиною: починаємо вантажити, щойно кнопку збираються натиснути
              onPointerEnter={preloadDayPicker}
              onPointerDown={preloadDayPicker}
              onFocus={preloadDayPicker}
            >
              <span className="truncate font-[650] figures">{compact ? shortRange(week.from, week.to) : formatDateRange(week.from, week.to)}</span>
            </Button>
          </PopoverTrigger>
        </Tooltip>
        <PopoverContent width="auto" align="center" className="p-2">
          <LazyDayPicker
            mode="single"
            today={localDateFromIso(today)}
            required
            selected={localDateFromIso(value)}
            defaultMonth={localDateFromIso(value)}
            startMonth={firstWeek ? localDateFromIso(firstWeek) : undefined}
            disabled={firstWeek ? { before: localDateFromIso(firstWeek) } : undefined}
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
          {/* ширина — як у календаря: текст переноситься, а не розтягує спливне вікно */}
          {firstWeek ? <p className="w-0 min-w-full px-1 pb-2 text-caption text-muted">{SCHEDULE_HISTORY_HINT}</p> : null}
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
      {!isCurrent && returnLink === 'below' ? (
        // на компʼютері керування вирівняні праворуч: посилання поза потоком, під датами, і перемикач не зсувається;
        // вужче керування вирівняні ліворуч — посилання просто стоїть у кінці рядка
        <Button
          variant="link"
          size="sm"
          onClick={() => onChange(home)}
          className="ms-2 hidden whitespace-nowrap sm:inline-flex lg:absolute lg:top-full lg:left-1/2 lg:ms-0 lg:mt-0.5 lg:-translate-x-1/2"
        >
          До цього тижня
        </Button>
      ) : null}
    </div>
  );
}
