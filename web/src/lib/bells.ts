/**
 * Стан навчального дня за розкладом дзвінків: «зараз 2 пара, до кінця 23 хв», «перерва до 11:00».
 * Чиста логіка без React — легко тестувати з фіксованим часом.
 */
import type { BellSlotDto } from '@/api/types';
import { timeToMinutes } from './date';

export interface TimedSlot {
  pair: number;
  start: number;
  end: number;
  startsAt: string;
  endsAt: string;
}

export type PairStatus =
  | { kind: 'no-classes' }
  | { kind: 'before-day'; next: TimedSlot; minutesUntil: number }
  | {
      kind: 'in-pair';
      current: TimedSlot;
      /** 0..1 — частка пари, що минула */
      progress: number;
      minutesLeft: number;
      next: TimedSlot | null;
    }
  | {
      kind: 'break';
      previous: TimedSlot;
      next: TimedSlot;
      minutesLeft: number;
      /** 0..1 — частка перерви, що минула */
      progress: number;
    }
  | { kind: 'after-day'; last: TimedSlot };

export function toTimedSlots(slots: readonly BellSlotDto[]): TimedSlot[] {
  return slots
    .map((slot) => ({
      pair: slot.pair,
      start: timeToMinutes(slot.startsAt),
      end: timeToMinutes(slot.endsAt),
      startsAt: slot.startsAt,
      endsAt: slot.endsAt,
    }))
    .filter((slot) => slot.end > slot.start)
    .sort((a, b) => a.start - b.start);
}

/**
 * Стан дня для моменту `nowMinutes` (хвилини від півночі, можна дробові — для плавного прогресу).
 * `onlyPairs` обмежує розрахунок парами, які справді є в людини (щоб «пари закінчилися» після останньої своєї).
 */
export function pairStatus(
  slots: readonly BellSlotDto[],
  nowMinutes: number,
  onlyPairs?: ReadonlySet<number> | null,
): PairStatus {
  const timed = toTimedSlots(slots).filter((slot) => !onlyPairs || onlyPairs.has(slot.pair));
  const first = timed[0];
  const last = timed[timed.length - 1];
  if (!first || !last) return { kind: 'no-classes' };

  if (nowMinutes < first.start) {
    return { kind: 'before-day', next: first, minutesUntil: Math.ceil(first.start - nowMinutes) };
  }
  if (nowMinutes >= last.end) return { kind: 'after-day', last };

  for (let i = 0; i < timed.length; i += 1) {
    const slot = timed[i] as TimedSlot;
    const next = timed[i + 1] ?? null;
    if (nowMinutes >= slot.start && nowMinutes < slot.end) {
      const length = slot.end - slot.start;
      return {
        kind: 'in-pair',
        current: slot,
        progress: clamp01((nowMinutes - slot.start) / length),
        minutesLeft: Math.ceil(slot.end - nowMinutes),
        next,
      };
    }
    if (next && nowMinutes >= slot.end && nowMinutes < next.start) {
      const length = next.start - slot.end;
      return {
        kind: 'break',
        previous: slot,
        next,
        minutesLeft: Math.ceil(next.start - nowMinutes),
        progress: clamp01((nowMinutes - slot.end) / length),
      };
    }
  }
  return { kind: 'after-day', last };
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** Короткий текст стану для живого рядка та aria-live. */
export function describePairStatus(status: PairStatus): string {
  switch (status.kind) {
    case 'no-classes':
      return 'Сьогодні пар немає';
    case 'before-day':
      return `Перша пара о ${status.next.startsAt}`;
    case 'in-pair':
      return `Зараз ${status.current.pair} пара, до кінця ${status.minutesLeft}\u00a0хв`;
    case 'break':
      return `Перерва до ${status.next.startsAt}, далі ${status.next.pair} пара`;
    case 'after-day':
      return 'Пари на сьогодні закінчилися';
  }
}
