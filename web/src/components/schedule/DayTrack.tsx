import { Fragment } from 'react';
import type { PairStatus, TimedSlot } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { Tooltip } from '../ui/Tooltip';
import { pillShift, segmentHint, type SegmentState } from './track-hint';

/**
 * Ширина доріжки, вужче за яку підпис часу вже не вміщається у свою колонку (≈ 3.25rem на пару).
 * Тоді підписи стоять одним рядком під доріжкою: поточна пара ліворуч, наступна праворуч.
 * Класи — буквальні рядки, щоб Tailwind їх побачив.
 */
const NARROW: Record<number, { columns: string; row: string }> = {
  1: { columns: '@max-[4rem]/track:hidden', row: '@max-[4rem]/track:flex' },
  2: { columns: '@max-[7rem]/track:hidden', row: '@max-[7rem]/track:flex' },
  3: { columns: '@max-[10rem]/track:hidden', row: '@max-[10rem]/track:flex' },
  4: { columns: '@max-[14rem]/track:hidden', row: '@max-[14rem]/track:flex' },
  5: { columns: '@max-[17rem]/track:hidden', row: '@max-[17rem]/track:flex' },
  6: { columns: '@max-[20rem]/track:hidden', row: '@max-[20rem]/track:flex' },
  7: { columns: '@max-[24rem]/track:hidden', row: '@max-[24rem]/track:flex' },
  8: { columns: '@max-[27rem]/track:hidden', row: '@max-[27rem]/track:flex' },
};
const NARROW_MAX = { columns: '@max-[32rem]/track:hidden', row: '@max-[32rem]/track:flex' };

function segmentState(slot: TimedSlot, minutes: number): SegmentState {
  if (minutes >= slot.end) return 'past';
  if (minutes >= slot.start) return 'current';
  return 'future';
}

/**
 * Доріжка дня на одній сітці з однаковими колонками для трьох рядків: пігулка «зараз», смуги пар,
 * підписи. Пари однакової ширини, проміжок 8 px (довга перерва ≥ 20 хв — 20 px): ритм без осі часу.
 * Підписів щонайбільше два, кожен у своїй колонці й обрізається в її межах — накластися вони не можуть.
 */
export function DayTrack({
  slots,
  status,
  minutes,
  ownPairs,
  className,
}: {
  slots: readonly TimedSlot[];
  status: PairStatus;
  minutes: number;
  ownPairs?: ReadonlySet<number> | null;
  className?: string;
}) {
  // колонки: пара, проміжок, пара, проміжок, …, пара
  const template = slots
    .map((slot, index) => {
      const next = slots[index + 1];
      if (!next) return 'minmax(0,1fr)';
      return `minmax(0,1fr) ${next.start - slot.end >= 20 ? '20px' : '8px'}`;
    })
    .join(' ');
  const column = (index: number) => index * 2 + 1;

  const currentIndex = status.kind === 'in-pair' ? slots.findIndex((slot) => slot.pair === status.current.pair) : -1;
  const breakIndex = status.kind === 'break' ? slots.findIndex((slot) => slot.pair === status.previous.pair) : -1;
  const nextIndex =
    status.kind === 'in-pair' && status.next
      ? slots.findIndex((slot) => slot.pair === status.next?.pair)
      : status.kind === 'break' || status.kind === 'before-day'
        ? slots.findIndex((slot) => slot.pair === status.next.pair)
        : -1;
  const progress = status.kind === 'in-pair' ? status.progress : 0;
  const clock = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(Math.floor(minutes % 60)).padStart(2, '0')}`;
  const showPill = currentIndex >= 0 || breakIndex >= 0;
  const narrow = NARROW[slots.length] ?? NARROW_MAX;
  const nextLabelTone = status.kind === 'break' || status.kind === 'before-day' ? 'font-semibold text-accent' : 'text-muted';

  return (
    <div aria-hidden="true" className={cn('@container/track flex flex-col gap-1.5', className)}>
      <div className="grid grid-rows-[1.25rem_0.5rem_1rem] gap-y-1.5" style={{ gridTemplateColumns: template }}>
        {/* рядок пігулки: лише «зараз», усередині колонки поточної пари або проміжку перерви */}
        {showPill ? (
          <span
            data-now-pill-cell=""
            // колонка — контейнер для 100cqw; overflow-x-clip — запобіжник: ніщо з пігулки не розширює сторінку,
            // а по вертикалі не обрізає (clip, на відміну від hidden, не вмикає прокрутку другої осі)
            className="@container relative row-start-1 min-w-0 overflow-x-clip"
            style={{ gridColumn: currentIndex >= 0 ? column(currentIndex) : column(breakIndex) + 1 }}
          >
            {currentIndex >= 0 ? (
              // рухається сама пігулка (56 px), а не невидима доріжка завширшки з колонку: зсув — частка
              // «колонка мінус пігулка», тож при progress = 1 правий край пігулки рівно на краю колонки.
              // Ковзає лише transform (1 с лінійно), без перерахунку макета.
              <span
                data-now-pill=""
                className="absolute start-0 top-0 flex h-5 w-14 items-center justify-center rounded-full bg-accent text-meta font-semibold text-on-accent figures transition-transform duration-1000 ease-linear"
                style={{ transform: pillShift(progress) }}
              >
                {clock}
              </span>
            ) : (
              <span className="absolute top-0 left-1/2 flex h-5 w-14 -translate-x-1/2 items-center justify-center rounded-full bg-accent text-meta font-semibold text-on-accent figures">
                {clock}
              </span>
            )}
          </span>
        ) : null}

        {slots.map((slot, index) => {
          const state = segmentState(slot, minutes);
          const isWindow = ownPairs ? !ownPairs.has(slot.pair) : false;
          const next = slots[index + 1];
          return (
            <Fragment key={slot.pair}>
              {/* наведення мишею на смугу називає пару й час (ціль вища за саму смугу: 8 px зверху й знизу) */}
              <Tooltip content={segmentHint(slot, state, isWindow)} side="bottom">
                <span data-track-segment="" className="row-start-2 -my-2 min-w-0 py-2" style={{ gridColumn: column(index) }}>
                  <span
                    className={cn(
                      'block h-2 overflow-hidden rounded-full',
                      // вікно — порожня пунктирна смуга і в минулому, і попереду: заняття тут не було й не буде
                      isWindow && state !== 'current' && 'border border-dashed border-default',
                      // минула пара — тонка смуга (половина висоти); під мишею стає повною, підказка каже час
                      !isWindow && state === 'past' && 'track-past bg-[var(--fill-track-past)]',
                      state === 'current' && 'bg-selected',
                      !isWindow && state === 'future' && 'bg-[var(--fill-track-future)]',
                    )}
                  >
                    {state === 'current' ? (
                      <span
                        className="block h-full origin-left rounded-full bg-accent transition-transform duration-1000 ease-linear rtl:origin-right"
                        style={{ transform: `scaleX(${Math.max(0.04, progress)})` }}
                      />
                    ) : null}
                  </span>
                </span>
              </Tooltip>
              {/* перерва зараз: проміжок між смугами світиться акцентом */}
              {next && index === breakIndex ? (
                <span className="row-start-2 h-2 rounded-full bg-accent" style={{ gridColumn: column(index) + 1 }} />
              ) : null}
            </Fragment>
          );
        })}

        {/* підписи: поточна пара (акцентом) і наступна (тихо), кожна — у своїй колонці */}
        {currentIndex >= 0 ? (
          <span
            className={cn(
              '@container row-start-3 min-w-0 overflow-clip text-caption leading-4 font-semibold whitespace-nowrap text-accent figures',
              narrow.columns,
            )}
            style={{ gridColumn: column(currentIndex) }}
          >
            {slots[currentIndex]?.startsAt}
            <span className="hidden @[5.25rem]:inline">–{slots[currentIndex]?.endsAt}</span>
          </span>
        ) : null}
        {nextIndex >= 0 ? (
          <span
            className={cn('row-start-3 min-w-0 overflow-clip text-caption leading-4 whitespace-nowrap figures', nextLabelTone, narrow.columns)}
            style={{ gridColumn: column(nextIndex) }}
          >
            {slots[nextIndex]?.startsAt}
          </span>
        ) : null}
      </div>
      {/* вузька доріжка: ті самі два підписи одним рядком — поточна пара ліворуч, наступна праворуч */}
      {currentIndex >= 0 || nextIndex >= 0 ? (
        <div className={cn('-mt-[1.375rem] hidden justify-between gap-3 text-caption leading-4 whitespace-nowrap figures', narrow.row)}>
          {currentIndex >= 0 ? (
            <span className="font-semibold text-accent">
              {slots[currentIndex]?.startsAt}–{slots[currentIndex]?.endsAt}
            </span>
          ) : (
            <span />
          )}
          {nextIndex >= 0 ? <span className={nextLabelTone}>далі {slots[nextIndex]?.startsAt}</span> : null}
        </div>
      ) : null}
    </div>
  );
}
