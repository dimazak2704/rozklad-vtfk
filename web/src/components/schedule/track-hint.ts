import type { TimedSlot } from '@/lib/bells';

export type SegmentState = 'past' | 'current' | 'future';

/** Підказка смуги доріжки дня: «3 пара · 11:00–12:20 · іде зараз». */
export function segmentHint(slot: Pick<TimedSlot, 'pair' | 'startsAt' | 'endsAt'>, state: SegmentState, isWindow = false): string {
  const tail = state === 'current' ? 'іде зараз' : isWindow ? 'пари немає' : state === 'past' ? 'минула' : null;
  return [`${slot.pair} пара`, `${slot.startsAt}–${slot.endsAt}`, tail].filter(Boolean).join(' · ');
}
