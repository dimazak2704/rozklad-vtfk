import { useMemo } from 'react';
import type { BellSlotDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { describePairStatus, pairStatus, toTimedSlots, type PairStatus, type TimedSlot } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { durationLabel } from '@/lib/labels';
import { StatusDot } from '../ui/StatusDot';

export interface NowIndicatorProps {
  /** Дзвінки дня (профіль, що діє). */
  bells: readonly BellSlotDto[];
  /** Пари, які в людини справді є: на них «зупинка», решту лінія проходить. */
  lessonPairs?: readonly number[];
  /** День на екрані; живий стан — лише для сьогодні. */
  date?: IsoDate | null;
  /** `strip` — лінія маршруту з зупинками, `inline` — один рядок із тонкою смужкою. */
  variant?: 'strip' | 'inline';
  /** Зафіксований момент (хвилини від півночі) — для демо й тестів. */
  at?: number;
  className?: string;
}

/** Позиція 0..1 на лінії: зупинки на рівній відстані, між ними — час пари й перерви. */
function linePosition(slots: readonly TimedSlot[], minutes: number): number {
  const last = slots[slots.length - 1];
  if (!last) return 0;
  const nodes = [...slots.map((s) => s.start), last.end];
  const segments = nodes.length - 1;
  if (minutes <= (nodes[0] ?? 0)) return 0;
  if (minutes >= (nodes[segments] ?? 0)) return 1;
  for (let i = 0; i < segments; i += 1) {
    const from = nodes[i] as number;
    const to = nodes[i + 1] as number;
    if (minutes >= from && minutes < to) return (i + (minutes - from) / (to - from)) / segments;
  }
  return 1;
}

/** Текст для aria-live: змінюється лише зі зміною стану, а не щохвилини. */
function liveText(status: PairStatus): string {
  if (status.kind === 'in-pair') return `Зараз ${status.current.pair} пара`;
  if (status.kind === 'break') return `Перерва до ${status.next.startsAt}`;
  return describePairStatus(status);
}

function Headline({ status }: { status: PairStatus }) {
  switch (status.kind) {
    case 'in-pair':
      return (
        <>
          <p className="flex items-baseline gap-2 text-ui text-secondary">
            <StatusDot tone="live" pulse label="Наживо" />
            Зараз <span className="font-condensed text-heading font-bold text-primary figures">{status.current.pair} пара</span>
            <span className="figures">
              {status.current.startsAt}–{status.current.endsAt}
            </span>
          </p>
          <p className="text-end">
            <span className="font-condensed text-display font-bold text-accent figures">{status.minutesLeft}</span>
            <span className="ms-1 text-ui text-secondary">хв до кінця</span>
          </p>
        </>
      );
    case 'break':
      return (
        <>
          <p className="flex items-baseline gap-2 text-ui text-secondary">
            <StatusDot tone="live" pulse label="Наживо" />
            Перерва до <span className="font-condensed text-heading font-bold text-primary figures">{status.next.startsAt}</span>
          </p>
          <p className="text-end">
            <span className="font-condensed text-display font-bold text-accent figures">{status.minutesLeft}</span>
            <span className="ms-1 text-ui text-secondary">хв до {status.next.pair} пари</span>
          </p>
        </>
      );
    case 'before-day':
      return (
        <>
          <p className="text-ui text-secondary">
            Пари ще не почалися. Перша — о{' '}
            <span className="font-condensed text-heading font-bold text-primary figures">{status.next.startsAt}</span>
          </p>
          {status.minutesUntil <= 180 ? (
            <p className="text-end text-ui text-secondary">
              через <span className="font-condensed text-heading font-bold text-primary figures">{durationLabel(status.minutesUntil)}</span>
            </p>
          ) : null}
        </>
      );
    case 'after-day':
      return <p className="text-ui text-secondary">Пари на сьогодні закінчилися о {status.last.endsAt}</p>;
    case 'no-classes':
      return <p className="text-ui text-secondary">Сьогодні пар немає</p>;
  }
}

/**
 * «Живий» стан дня — підпис світу трамвайного розкладу: пари як зупинки на лінії,
 * маркер показує, де день зараз; праворуч — великий зворотний відлік.
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

  const announcement = describePairStatus(status);
  const position = linePosition(slots, minutes);
  const markerVisible = status.kind === 'in-pair' || status.kind === 'break';

  if (variant === 'inline') {
    const progress = status.kind === 'in-pair' || status.kind === 'break' ? status.progress : status.kind === 'after-day' ? 1 : 0;
    return (
      <div className={cn('flex min-w-0 flex-col gap-1.5', className)}>
        <p className="flex items-center gap-2 text-ui text-primary">
          {markerVisible ? <StatusDot tone="live" pulse label="Наживо" /> : null}
          <span className="truncate">{announcement}</span>
        </p>
        <div aria-hidden="true" className="h-1 overflow-hidden rounded-full bg-sunken">
          <div className="h-full origin-left bg-accent transition-transform duration-1000 ease-linear rtl:origin-right" style={{ transform: `scaleX(${progress})` }} />
        </div>
        <span className="sr-only" aria-live="polite">
          {liveText(status)}
        </span>
      </div>
    );
  }

  const segments = slots.length;
  return (
    <section aria-label="Стан навчального дня" className={cn('flex flex-col gap-4', className)}>
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-1">
        <Headline status={status} />
      </div>

      <div className="relative px-3 pt-1 pb-9" aria-hidden="true">
        {/* лінія маршруту: пройдена частина — «живим» кольором */}
        <div className="relative h-1.5">
          <div className="absolute inset-0 rounded-full bg-[var(--border-color-default)]" />
          <div
            className="absolute inset-y-0 start-0 rounded-full bg-accent transition-[width] duration-1000 ease-linear"
            style={{ width: `${position * 100}%` }}
          />
          {slots.map((slot, index) => {
            const x = (index / segments) * 100;
            const passed = minutes >= slot.end;
            const current = status.kind === 'in-pair' && status.current.pair === slot.pair;
            const hasLesson = !ownPairs || ownPairs.has(slot.pair);
            return (
              <div
                key={slot.pair}
                className="absolute top-1/2 -translate-x-1/2 -translate-y-1/2 rtl:translate-x-1/2"
                style={{ insetInlineStart: `${x}%` }}
              >
                <span
                  className={cn(
                    'block rounded-full border-2',
                    hasLesson ? 'size-4' : 'size-2.5',
                    current
                      ? 'border-accent bg-[var(--fill-surface)]'
                      : passed
                        ? 'border-accent bg-accent'
                        : hasLesson
                          ? 'border-strong bg-[var(--fill-surface)]'
                          : 'border-default bg-[var(--fill-surface)]',
                  )}
                />
                <span
                  className={cn(
                    'absolute top-6 left-1/2 flex -translate-x-1/2 flex-col items-center leading-none whitespace-nowrap',
                    hasLesson ? 'text-primary' : 'text-muted',
                  )}
                >
                  <span className={cn('font-condensed text-ui font-bold figures', current && 'text-accent')}>{slot.pair}</span>
                  <span className="mt-1 text-meta text-muted figures">{slot.startsAt}</span>
                </span>
              </div>
            );
          })}
          {/* кінцева: завершення останньої пари */}
          <span className="absolute end-0 top-1/2 h-3.5 w-1 -translate-y-1/2 rounded-full bg-[var(--border-color-strong)]" />
          {markerVisible ? (
            <span
              className="absolute top-1/2 size-5 -translate-x-1/2 -translate-y-1/2 rounded-full border-[3px] border-[var(--fill-surface)] bg-accent shadow-lift motion-safe:animate-live rtl:translate-x-1/2"
              style={{ insetInlineStart: `${position * 100}%` }}
            />
          ) : null}
        </div>
      </div>
      <p className="sr-only" aria-live="polite">
        {liveText(status)}
      </p>
    </section>
  );
}
