import { ArrowLeftRight } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { useMemo, type ComponentPropsWithRef, type ReactElement } from 'react';
import type { CalendarPeriod, CalendarWeek } from '@/api/calendar';
import type { IsoDate, PeriodEffect } from '@/api/types';
import { HoverDetails, ParityMark, Tooltip } from '@/components/ui';
import { cn } from '@/lib/cn';
import { addDays, compareIsoDates, formatDateRange, formatNumericDate, parseIsoDate, startOfWeek } from '@/lib/date';
import { MONTHS_NOMINATIVE, PARITY_LABEL } from '@/lib/labels';
import { KindSwatch } from './KindSwatch';
import { STUDY_MODE_LABEL } from './labels';
import { parityActionLabel } from './parity-confirmation';
import { periodAudience, periodChipLabel, periodKindOf } from './periods';

interface SemesterTimelineProps {
  weeks: readonly CalendarWeek[];
  periods: readonly CalendarPeriod[];
  today: IsoDate;
  /**
   * Поміняти парність тижня (null — недоступно, наприклад під час збереження). Натиск на тиждень лише відкриває
   * меню з цією дією, а сторінка ще перепитує: парність міняють рідко, і випадковий клік не має перебудувати розклад.
   */
  onToggleWeek: ((week: CalendarWeek) => void) | null;
  onOpenPeriod: (period: CalendarPeriod) => void;
}

/**
 * Смуга періоду тоном його ефекту: вихідний — червоний, «група не на парах» — бурштин, форма навчання — блакитний,
 * позначка — акцентний. Колір виду з графіка — крапкою на початку смуги.
 * Тло — низькохроматичний «wash» (у темній темі C ≤ 0,03), колір несуть рамка 1 px і слово.
 */
const PARITY_SHORT_WORD = { NUMERATOR: 'чис', DENOMINATOR: 'знам' } as const;

const MENU_ITEM = cn(
  'flex min-h-9 cursor-default select-none items-center gap-2.5 rounded-control px-2.5 py-2 text-ui text-primary outline-none',
  'data-highlighted:bg-hover data-disabled:text-disabled pointer-coarse:min-h-11',
);

/**
 * Меню тижня: що це за тиждень і одна дія — поміняти парність (з підтвердженням на сторінці).
 * Решту пропсів і ref пропускаємо на тригер: зовнішній `Tooltip` (Slot) вішає свої обробники й ref саме
 * сюди, і без цього підказка тижня ніколи не відкривається.
 */
function WeekMenu({
  week,
  onToggleWeek,
  children,
  ...triggerProps
}: Omit<ComponentPropsWithRef<'button'>, 'children'> & {
  week: CalendarWeek;
  onToggleWeek: ((week: CalendarWeek) => void) | null;
  children: ReactElement;
}) {
  return (
    <DropdownMenu.Root modal={false}>
      <DropdownMenu.Trigger asChild {...triggerProps}>
        {children}
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="start"
          sideOffset={6}
          collisionPadding={8}
          className="anim-pop z-50 min-w-60 rounded-popover border border-default bg-raised p-1.5 text-primary shadow-popover"
        >
          <DropdownMenu.Label className="px-2.5 pt-1.5 pb-1 text-caption text-muted">
            {week.weekNumber} тиждень · {formatDateRange(week.weekStart, week.weekEnd)} · {PARITY_LABEL[week.parity]}
            {week.overridden ? ' (вручну)' : ''}
          </DropdownMenu.Label>
          <DropdownMenu.Item disabled={!onToggleWeek} onSelect={() => onToggleWeek?.(week)} className={MENU_ITEM}>
            <ArrowLeftRight aria-hidden="true" className="size-4 text-muted" />
            {parityActionLabel(week)}
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

const BAR_TONE: Record<PeriodEffect, string> = {
  DAY_OFF: 'border-cancel bg-cancel-subtle text-cancel',
  SUSPEND: 'border-change bg-change-subtle text-change',
  ONLINE: 'border-room bg-room-subtle text-room',
  INFO: 'border-accent-subtle bg-selected text-accent',
};

/**
 * Семестр смугою тижнів з чисельником/знаменником (меню тижня — змінити парність вручну) і під ними —
 * періоди календаря смугами. Видно одразу, де практика, де дистанційка. На телефоні — список тижнів.
 */
export function SemesterTimeline({ weeks, periods, today, onToggleWeek, onOpenPeriod }: SemesterTimelineProps) {
  const currentWeek = startOfWeek(today);
  const months = useMemo(() => {
    const out: Array<{ index: number; label: string }> = [];
    let previous = -1;
    weeks.forEach((week, index) => {
      // тиждень належить місяцю свого четверга (як ISO-тижні): 31.08–06.09 — це вже вересень
      const shown = parseIsoDate(addDays(week.weekStart, 3)).month;
      if (shown !== previous) {
        out.push({ index, label: MONTHS_NOMINATIVE[shown - 1] ?? '' });
        previous = shown;
      }
    });
    return out;
  }, [weeks]);

  const bars = useMemo(
    () =>
      periods
        .map((period) => {
          const first = weeks.findIndex((w) => compareIsoDates(w.weekEnd, period.startsOn) >= 0);
          const lastFound = weeks.findLastIndex((w) => compareIsoDates(w.weekStart, period.endsOn) <= 0);
          return { period, kind: periodKindOf(period), first, last: lastFound };
        })
        .filter((bar) => bar.first >= 0 && bar.last >= bar.first),
    [periods, weeks],
  );

  const columns = `repeat(${weeks.length}, minmax(2.5rem, 1fr))`;

  return (
    <>
    {/* телефон і планшет: смуга з 18 тижнів не вміщується, а прокрутку вбік ніхто не помічає — тут список тижнів */}
    <ol aria-label="Тижні семестру" className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface lg:hidden">
      {weeks.map((week) => {
        const isCurrent = week.weekStart === currentWeek;
        // період називаємо один раз — у тижні, коли він починається (або в першому тижні семестру)
        const during = periods.filter(
          (p) =>
            compareIsoDates(p.startsOn, week.weekEnd) <= 0 &&
            compareIsoDates(p.endsOn, week.weekStart) >= 0 &&
            (compareIsoDates(p.startsOn, week.weekStart) >= 0 || week === weeks[0]),
        );
        return (
          <li key={week.weekStart} aria-current={isCurrent ? 'date' : undefined} className={cn('flex items-center gap-3 px-4 py-2.5', isCurrent && 'bg-today-wash')}>
            <span className="w-7 text-heading leading-none font-semibold figures text-primary">{week.weekNumber}</span>
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="text-ui text-primary">
                {formatDateRange(week.weekStart, week.weekEnd)}
                {isCurrent ? <span className="text-caption text-accent"> · цей тиждень</span> : null}
              </span>
              {during.length > 0 ? (
                <span className="flex min-w-0 flex-col">
                  {during.map((period) => (
                    <button
                      key={period.id}
                      type="button"
                      onClick={() => onOpenPeriod(period)}
                      className="truncate rounded-tag text-start text-caption text-secondary hover-fine:text-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
                    >
                      {periodChipLabel(period)}: {period.title} — до {formatNumericDate(period.endsOn).slice(0, 5)}
                    </button>
                  ))}
                </span>
              ) : null}
            </span>
            <WeekMenu week={week} onToggleWeek={onToggleWeek}>
              <button
                type="button"
                aria-label={`${week.weekNumber} тиждень: ${PARITY_LABEL[week.parity]}${week.overridden ? ' (змінено вручну)' : ''}. Меню тижня`}
                className={cn(
                  'inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-control px-2 text-ui font-[550] hover-fine:bg-hover',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                  week.overridden ? 'text-accent' : 'text-secondary',
                )}
              >
                <ParityMark parity={week.parity} className="h-3.5 w-3" />
                {PARITY_LABEL[week.parity]}
              </button>
            </WeekMenu>
          </li>
        );
      })}
    </ol>
    <div className="overflow-x-auto overscroll-x-contain rounded-panel border border-default bg-surface max-lg:hidden">
      <div
        className="p-3"
        // тижні ділять ширину порівну; вужче за 2,5rem на тиждень — горизонтальна прокрутка
        style={{ display: 'grid', gridTemplateColumns: columns, columnGap: '0.25rem', rowGap: '0.375rem', minWidth: `${weeks.length * 2.75 + 1.5}rem` }}
      >
        {months.map((month, i) => (
          <span
            key={`${month.label}-${month.index}`}
            className="text-meta font-semibold text-muted first-letter:uppercase"
            style={{ gridRow: 1, gridColumn: `${month.index + 1} / ${(months[i + 1]?.index ?? weeks.length) + 1}` }}
          >
            {month.label}
          </span>
        ))}
        {weeks.map((week, index) => {
          const isCurrent = week.weekStart === currentWeek;
          const hint = `${week.weekNumber} тиждень, ${formatDateRange(week.weekStart, week.weekEnd)}: ${PARITY_LABEL[week.parity]}${
            week.overridden ? ' (змінено вручну)' : ''
          }.`;
          return (
            <Tooltip key={week.weekStart} content={hint}>
              <WeekMenu week={week} onToggleWeek={onToggleWeek}>
                <button
                  type="button"
                  aria-current={isCurrent ? 'date' : undefined}
                  aria-label={`${hint} Меню тижня`}
                  style={{ gridRow: 2, gridColumn: index + 1 }}
                  // `data-state` на кнопці ставить підказка, тож відкрите меню видно за `aria-expanded`
                  className={cn(
                    'group relative flex h-12 cursor-pointer flex-col items-center justify-center gap-1 rounded-control border text-center',
                    'transition-[background-color,border-color] duration-150 ease-out hover-fine:bg-hover aria-expanded:bg-hover',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                    week.parity === 'DENOMINATOR' ? 'bg-sunken' : 'bg-surface',
                    week.overridden ? 'border-accent' : 'border-default hover-fine:border-strong',
                    isCurrent && 'ring-2 ring-[var(--border-color-accent)] ring-offset-1 ring-offset-[var(--background-color-surface)]',
                  )}
                >
                  <span className="text-heading leading-none font-bold figures text-primary">{week.weekNumber}</span>
                  {/* номер і коротке слово парності — читається з першого погляду; дата — у підказці */}
                  <span aria-hidden="true" className={cn('text-meta leading-none font-[550]', week.overridden ? 'text-accent' : 'text-secondary')}>
                    {PARITY_SHORT_WORD[week.parity]}
                  </span>
                  {week.overridden ? (
                    <span aria-hidden="true" className="absolute -top-1 -right-1 size-2.5 rounded-full border-2 border-[var(--background-color-surface)] bg-accent" />
                  ) : null}
                </button>
              </WeekMenu>
            </Tooltip>
          );
        })}
        {bars.map(({ period, kind, first, last }, row) => (
          <HoverDetails
            key={period.id}
            label={`Період: ${period.title}`}
            content={
              <div className="flex flex-col gap-1.5 text-ui">
                <p className="font-semibold text-primary">{period.title}</p>
                <p className="text-secondary">
                  {kind.name}
                  {period.studyMode ? ` · ${STUDY_MODE_LABEL[period.studyMode]}` : ''} · {periodAudience(period)}
                  {period.room ? ` · ауд. ${period.room.name}` : ''}
                </p>
                <p className="text-caption text-muted figures">
                  {formatNumericDate(period.startsOn)} – {formatNumericDate(period.endsOn)}
                </p>
                {period.orderNote ? <p className="text-caption text-secondary">У розпорядженні: «{period.orderNote}»</p> : null}
              </div>
            }
          >
            <button
              type="button"
              onClick={() => onOpenPeriod(period)}
              style={{ gridRow: row + 3, gridColumn: `${first + 1} / ${last + 2}` }}
              className={cn(
                'flex h-7 min-w-0 items-center gap-1.5 truncate rounded-tag border px-2 text-start text-caption font-semibold',
                'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
                BAR_TONE[kind.effect],
              )}
            >
              {kind.color ? <KindSwatch color={kind.color} effect={kind.effect} /> : null}
              {/* у смузі — кого й який вид («4КІ1 · НС»): однакові назви інакше не розрізнити; повна назва — у підказці */}
              <span className="truncate">{periodChipLabel(period)}</span>
            </button>
          </HoverDetails>
        ))}
      </div>
    </div>
    </>
  );
}
