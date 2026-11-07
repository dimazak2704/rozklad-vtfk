import type { LessonTone } from '@/lib/schedule';
import type { BadgeTone } from '../ui/Badge';

/** Тон бейджа для стану заняття. */
export const TONE_BADGE: Record<LessonTone, BadgeTone> = {
  regular: 'neutral',
  change: 'change',
  cancel: 'cancel',
  add: 'add',
  room: 'room',
  block: 'neutral',
};
