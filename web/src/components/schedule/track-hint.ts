import type { TimedSlot } from '@/lib/bells';

export type SegmentState = 'past' | 'current' | 'future';

/**
 * Підказка смуги доріжки дня: «3 пара · 11:00–12:20 · іде зараз». Вікно (у людини цієї пари немає)
 * не читається як заняття: минуле — «пари не було», поточне й майбутнє — «пари немає».
 */
export function segmentHint(slot: Pick<TimedSlot, 'pair' | 'startsAt' | 'endsAt'>, state: SegmentState, isWindow = false): string {
  const tail = isWindow
    ? state === 'past'
      ? 'пари не було'
      : 'пари немає'
    : state === 'current'
      ? 'іде зараз'
      : state === 'past'
        ? 'минула'
        : null;
  return [`${slot.pair} пара`, `${slot.startsAt}–${slot.endsAt}`, tail].filter(Boolean).join(' · ');
}

/** Зсув пігулки «зараз» у колонці пари: від лівого краю (0) до правого (1), пігулка ніколи не виходить за колонку. */
export function pillShift(progress: number): string {
  const clamped = Math.min(1, Math.max(0, progress));
  return `translateX(calc((100cqw - 3.5rem) * ${clamped}))`;
}
