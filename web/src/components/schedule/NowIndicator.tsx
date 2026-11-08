import { useMemo } from 'react';
import type { BellSlotDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { describePairStatus, pairStatus, toTimedSlots, type PairStatus, type TimedSlot } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { durationLabel } from '@/lib/labels';
import { pairOrdinalGenitive, pairOrdinalLabel } from '@/lib/ordinal';

export interface NowIndicatorProps {
  /** Дзвінки дня (профіль, що діє). */
  bells: readonly BellSlotDto[];
  /** Пари, які в людини справді є: на них «зупинка», решту лінія проходить. */
  lessonPairs?: readonly number[];
  /** День на екрані; живий стан — лише для сьогодні. */
  date?: IsoDate | null;
  /**
   * `strip` — лінія маршруту з зупинками в масштабі часу (сторінка дзвінків);
   * `compact` — блок над денним списком на телефоні: «Зараз 2-га пара» і великий відлік;
   * `inline` — один рядок у рядку даних заголовка сторінки (десктоп).
   */
  variant?: 'strip' | 'compact' | 'inline';
  /** Зафіксований момент (хвилини від півночі) — для демо й тестів. */
  at?: number;
  className?: string;
}

/** Текст для aria-live: змінюється лише зі зміною стану, а не щохвилини. */
function liveText(status: PairStatus): string {
  if (status.kind === 'in-pair') return `Зараз ${pairOrdinalLabel(status.current.pair)}`;
  if (status.kind === 'break') return `Перерва до ${status.next.startsAt}`;
  return describePairStatus(status);
}

/** Один рядок: «Зараз 2-га пара · ще 35 хв». */
function inlineText(status: PairStatus): string {
  switch (status.kind) {
    case 'in-pair':
      return `Зараз ${pairOrdinalLabel(status.current.pair)} · ще ${status.minutesLeft}\u00a0хв`;
    case 'break':
      return `Перерва · ${pairOrdinalLabel(status.next.pair)} о ${status.next.startsAt}`;
    case 'before-day':
      return `Перша пара о ${status.next.startsAt}`;
    case 'after-day':
      return `Пари закінчилися о ${status.last.endsAt}`;
    case 'no-classes':
      return 'Сьогодні пар немає';
  }
}

/**
 * Стан дня одним стовпчиком від лівого краю: що зараз (14 px) і під ним великий відлік з підписом
 * на спільній базовій лінії — як температура й підпис у «Погоді».
 */
function Headline({ status }: { status: PairStatus }) {
  const numeral = 'font-condensed text-display font-bold text-accent figures';
  switch (status.kind) {
    case 'in-pair':
      return (
        <div className="flex flex-col">
          <p className="text-ui text-secondary figures">
            Зараз <span className="font-semibold text-primary">{pairOrdinalLabel(status.current.pair)}</span>
            <span aria-hidden="true"> · </span>
            {status.current.startsAt}–{status.current.endsAt}
          </p>
          <p className="flex items-baseline gap-2">
            <span className={numeral}>{status.minutesLeft}</span>
            <span className="text-body text-secondary">хв до кінця</span>
          </p>
        </div>
      );
    case 'break':
      return (
        <div className="flex flex-col">
          <p className="text-ui text-secondary figures">
            Перерва до <span className="font-semibold text-primary">{status.next.startsAt}</span>
          </p>
          <p className="flex items-baseline gap-2">
            <span className={numeral}>{status.minutesLeft}</span>
            <span className="text-body text-secondary">хв до {pairOrdinalGenitive(status.next.pair)} пари</span>
          </p>
        </div>
      );
    case 'before-day':
      return (
        <div className="flex flex-col">
          <p className="text-ui text-secondary figures">
            Пари ще не почалися. Перша — о <span className="font-semibold text-primary">{status.next.startsAt}</span>
          </p>
          {status.minutesUntil <= 180 ? (
            <p className="text-body text-secondary">
              через <span className="font-semibold text-primary figures">{durationLabel(status.minutesUntil)}</span>
            </p>
          ) : null}
        </div>
      );
    case 'after-day':
      return <p className="text-ui text-secondary">Пари на сьогодні закінчилися о {status.last.endsAt}</p>;
    case 'no-classes':
      return <p className="text-ui text-secondary">Сьогодні пар немає</p>;
  }
}

/** Підпис зупинки: два рядки на одній сітці для всіх зупинок, зокрема «кінець». */
function StopLabel({
  title,
  time,
  align,
  muted = false,
}: {
  title: string;
  time: string;
  align: 'start' | 'center' | 'end';
  muted?: boolean;
}) {
  return (
    <span
      className={cn(
        'absolute top-3 flex flex-col whitespace-nowrap',
        align === 'start' && 'start-0 items-start',
        align === 'center' && '-translate-x-1/2 items-center rtl:translate-x-1/2',
        align === 'end' && 'end-0 items-end',
      )}
    >
      <span className={cn('text-ui font-semibold figures', muted ? 'text-muted' : 'text-primary')}>{title}</span>
      <span className="text-caption text-muted figures">{time}</span>
    </span>
  );
}

/** Позиція 0..1 у масштабі часу дня. */
function scale(slots: readonly TimedSlot[]): (minutes: number) => number {
  const start = slots[0]?.start ?? 0;
  const end = slots[slots.length - 1]?.end ?? start + 1;
  const span = Math.max(1, end - start);
  return (minutes) => Math.min(1, Math.max(0, (minutes - start) / span));
}

/**
 * «Живий» стан дня. На сторінці дзвінків — лінія маршруту в масштабі часу: пари — суцільні відрізки 4 px,
 * перерви — пунктир 2 px, поточна зупинка — єдина синя крапка з однією хвилею. На телефоні — компактний
 * блок над списком, на десктопі — один рядок у заголовку; прогрес тоді живе в самій клітинці пари.
 */
export function NowIndicator({ bells, lessonPairs, date = null, variant = 'strip', at, className }: NowIndicatorProps) {
  const ownPairs = useMemo(() => (lessonPairs ? new Set(lessonPairs) : null), [lessonPairs]);
  const live = useNow(at === undefined ? bells : null, { date, onlyPairs: ownPairs });
  const status: PairStatus | null = at !== undefined ? pairStatus(bells, at, ownPairs) : live.status;
  const minutes = at ?? live.clock.minutes + live.clock.seconds / 60;
  const showLive = at !== undefined || live.isToday;

  const slots = useMemo(() => {
    const all = toTimedSlots(bells);
    if (!ownPairs || ownPairs.size === 0) return all;
    const pairs = [...ownPairs];
    const first = Math.min(...pairs);
    const last = Math.max(...pairs);
    return all.filter((slot) => slot.pair >= first && slot.pair <= last);
  }, [bells, ownPairs]);

  if (!showLive || !status || slots.length === 0) return null;

  const announcement = (
    <span className="sr-only" aria-live="polite">
      {liveText(status)}
    </span>
  );

  if (variant === 'inline') {
    return (
      <p className={cn('flex min-w-0 items-center gap-2 text-ui text-secondary figures', className)}>
        <span className="truncate">{inlineText(status)}</span>
        {announcement}
      </p>
    );
  }

  if (variant === 'compact') {
    return (
      <section aria-label="Стан навчального дня" className={className}>
        <Headline status={status} />
        {announcement}
      </section>
    );
  }

  const x = scale(slots);
  const lastSlot = slots[slots.length - 1] as TimedSlot;
  const currentPair = status.kind === 'in-pair' ? status.current.pair : null;

  return (
    <section aria-label="Стан навчального дня" className={cn('flex w-full flex-col gap-4', className)}>
      <Headline status={status} />

      <div className="relative mx-1.5 mb-1 h-14" aria-hidden="true">
        {/* відрізки: пари суцільні 4 px, перерви — пунктир 2 px, що відступає на задній план; пройдене — синім */}
        {slots.map((slot, index) => {
          const next = slots[index + 1];
          const from = x(slot.start);
          const to = x(slot.end);
          const passed = Math.min(1, Math.max(0, (minutes - slot.start) / (slot.end - slot.start)));
          const breakPassed = next ? minutes >= next.start : false;
          return (
            <span key={slot.pair}>
              <span
                className="absolute top-1.5 h-1 -translate-y-1/2 overflow-hidden rounded-full bg-[var(--border-color-default)]"
                style={{ insetInlineStart: `${from * 100}%`, width: `${(to - from) * 100}%` }}
              >
                <span
                  className="block h-full origin-left bg-accent transition-transform duration-1000 ease-linear rtl:origin-right"
                  style={{ transform: `scaleX(${passed})` }}
                />
              </span>
              {next ? (
                <span
                  className={cn(
                    'absolute top-1.5 h-0.5 -translate-y-1/2',
                    breakPassed
                      ? '[background-image:repeating-linear-gradient(90deg,var(--fill-accent)_0_2px,transparent_2px_6px)]'
                      : '[background-image:repeating-linear-gradient(90deg,var(--stroke-rule)_0_2px,transparent_2px_6px)]',
                  )}
                  style={{ insetInlineStart: `${to * 100}%`, width: `${(x(next.start) - to) * 100}%` }}
                />
              ) : null}
            </span>
          );
        })}

        {/* зупинки: два стани — кільце 8 px (пройдене заповнене) і поточна 12 px з єдиною хвилею */}
        {slots.map((slot, index) => {
          const hasLesson = !ownPairs || ownPairs.has(slot.pair);
          const current = currentPair === slot.pair;
          const passed = minutes >= slot.start && !current;
          return (
            <span key={slot.pair} className="absolute top-1.5" style={{ insetInlineStart: `${x(slot.start) * 100}%` }}>
              <span
                key={current ? `now-${slot.pair}` : slot.pair}
                className={cn(
                  'absolute top-0 block -translate-x-1/2 -translate-y-1/2 rounded-full rtl:translate-x-1/2',
                  current
                    ? 'live-ring size-3 bg-accent'
                    : passed
                      ? 'size-2 bg-accent'
                      : 'size-2 border-[1.5px] border-[var(--stroke-muted)] bg-[var(--fill-surface)]',
                )}
              />
              <StopLabel title={String(slot.pair)} time={slot.startsAt} align={index === 0 ? 'start' : 'center'} muted={!hasLesson} />
            </span>
          );
        })}

        {/* кінцева: вертикальна риска, слово й час завершення останньої пари — на тій самій сітці */}
        <span className="absolute end-0 top-1.5">
          <span className="absolute end-0 top-0 block h-3 w-0.5 -translate-y-1/2 rounded-full bg-[var(--border-color-strong)]" />
          <StopLabel title="кінець" time={lastSlot.endsAt} align="end" />
        </span>
      </div>
      {announcement}
    </section>
  );
}
