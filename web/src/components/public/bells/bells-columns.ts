/**
 * Профіль дзвінків колонками: дні з однаковим розкладом об'єднуються («Понеділок – четвер», «Пʼятниця»),
 * як на аркуші «дзвінки» таблиці розкладу.
 */
import type { BellProfileDto, BellSlotDto, Weekday } from '@/api/types';
import { timeToMinutes } from '@/lib/date';
import { capitalize, weekdayName } from '@/lib/labels';

export interface BellColumn {
  weekdays: Weekday[];
  /** «Понеділок – четвер», «Пʼятниця». */
  label: string;
  /** «Пн–Чт», «Пт». */
  short: string;
  slots: BellSlotDto[];
}

const SHORT = ['', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт'] as const;

function slotsOf(profile: BellProfileDto, weekday: Weekday): BellSlotDto[] {
  return profile.slots
    .filter((slot) => slot.weekday === weekday)
    .map(({ pair, startsAt, endsAt }) => ({ pair, startsAt, endsAt }))
    .sort((a, b) => a.pair - b.pair);
}

const sameSlots = (a: readonly BellSlotDto[], b: readonly BellSlotDto[]) =>
  a.length === b.length && a.every((slot, i) => slot.startsAt === b[i]?.startsAt && slot.endsAt === b[i]?.endsAt && slot.pair === b[i]?.pair);

export function bellColumns(profile: BellProfileDto): BellColumn[] {
  const columns: BellColumn[] = [];
  for (const weekday of [1, 2, 3, 4, 5] as const) {
    const slots = slotsOf(profile, weekday);
    if (slots.length === 0) continue;
    const last = columns[columns.length - 1];
    if (last && last.weekdays[last.weekdays.length - 1] === weekday - 1 && sameSlots(last.slots, slots)) {
      last.weekdays.push(weekday);
    } else {
      columns.push({ weekdays: [weekday], label: '', short: '', slots });
    }
  }
  for (const column of columns) {
    const first = column.weekdays[0] as Weekday;
    const lastDay = column.weekdays[column.weekdays.length - 1] as Weekday;
    column.label = first === lastDay ? capitalize(weekdayName(first)) : `${capitalize(weekdayName(first))} – ${weekdayName(lastDay)}`;
    column.short = first === lastDay ? SHORT[first] : `${SHORT[first]}–${SHORT[lastDay]}`;
  }
  return columns;
}

/** Перерва після пари (хвилини) або null, якщо пара остання. */
export function breakAfter(slots: readonly BellSlotDto[], pair: number): number | null {
  const index = slots.findIndex((slot) => slot.pair === pair);
  const current = slots[index];
  const next = slots[index + 1];
  if (!current || !next) return null;
  return timeToMinutes(next.startsAt) - timeToMinutes(current.endsAt);
}

export function pairMinutes(slot: BellSlotDto): number {
  return timeToMinutes(slot.endsAt) - timeToMinutes(slot.startsAt);
}
