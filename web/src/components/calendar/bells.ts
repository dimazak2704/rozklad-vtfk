/**
 * Модель редактора дзвінків: профіль — пари за днями тижня. У коледжі пн–чт зазвичай однакові,
 * а пʼятниця коротша (DOMAIN §2), тому редактор вміє тримати пн–чт однією колонкою.
 */
import type { BellProfileSlotDto, Weekday } from '@/api/types';
import { minutesToTime, timeToMinutes } from '@/lib/date';

export interface SlotDraft {
  pair: number;
  startsAt: string;
  endsAt: string;
}

export const WEEKDAYS: readonly Weekday[] = [1, 2, 3, 4, 5];
const MON_THU: readonly Weekday[] = [1, 2, 3, 4];
const TIME_RE = /^\d{2}:\d{2}$/;

export interface BellGrid {
  days: Record<Weekday, SlotDraft[]>;
  /** Пн–чт однакові — редагуємо одну колонку. */
  sameMonThu: boolean;
}

const sameSlots = (a: readonly SlotDraft[], b: readonly SlotDraft[]) =>
  a.length === b.length && a.every((slot, i) => slot.startsAt === b[i]?.startsAt && slot.endsAt === b[i]?.endsAt);

export function gridFromSlots(slots: readonly BellProfileSlotDto[]): BellGrid {
  const days = Object.fromEntries(
    WEEKDAYS.map((weekday) => [
      weekday,
      slots
        .filter((s) => s.weekday === weekday)
        .sort((a, b) => a.pair - b.pair)
        .map(({ pair, startsAt, endsAt }) => ({ pair, startsAt, endsAt })),
    ]),
  ) as Record<Weekday, SlotDraft[]>;
  const sameMonThu = MON_THU.every((weekday) => sameSlots(days[weekday], days[1]));
  return { days, sameMonThu };
}

/** Слоти для PUT: у режимі «пн–чт однакові» понеділок копіюється на вт–чт. */
export function slotsFromGrid(grid: BellGrid): BellProfileSlotDto[] {
  return WEEKDAYS.flatMap((weekday) => {
    const source = grid.sameMonThu && weekday !== 5 ? grid.days[1] : grid.days[weekday];
    return source.map((slot, index) => ({ weekday, pair: index + 1, startsAt: slot.startsAt, endsAt: slot.endsAt }));
  });
}

/** Помилки рядків дня: індекс → текст. Ті самі правила, що в core (BellProfileService.validateSlots). */
export function validateDay(slots: readonly SlotDraft[]): Map<number, string> {
  const errors = new Map<number, string>();
  slots.forEach((slot, index) => {
    if (!TIME_RE.test(slot.startsAt) || !TIME_RE.test(slot.endsAt)) {
      errors.set(index, 'Вкажіть початок і кінець пари');
      return;
    }
    if (timeToMinutes(slot.endsAt) <= timeToMinutes(slot.startsAt)) {
      errors.set(index, 'Пара закінчується раніше, ніж починається');
      return;
    }
    const previous = slots[index - 1];
    if (previous && TIME_RE.test(previous.endsAt) && timeToMinutes(slot.startsAt) < timeToMinutes(previous.endsAt)) {
      errors.set(index, `Починається раніше, ніж закінчується ${index} пара`);
    }
  });
  return errors;
}

export function hasErrors(grid: BellGrid): boolean {
  const days = grid.sameMonThu ? [grid.days[1], grid.days[5]] : WEEKDAYS.map((d) => grid.days[d]);
  return days.some((slots) => slots.length === 0 || validateDay(slots).size > 0);
}

/** Перерва після пари (хв) або null, якщо час ще не заповнено. */
export function breakAfter(slots: readonly SlotDraft[], index: number): number | null {
  const current = slots[index];
  const next = slots[index + 1];
  if (!current || !next || !TIME_RE.test(current.endsAt) || !TIME_RE.test(next.startsAt)) return null;
  return timeToMinutes(next.startsAt) - timeToMinutes(current.endsAt);
}

/** Тривалість пари (хв) або null. */
export function duration(slot: SlotDraft): number | null {
  if (!TIME_RE.test(slot.startsAt) || !TIME_RE.test(slot.endsAt)) return null;
  return timeToMinutes(slot.endsAt) - timeToMinutes(slot.startsAt);
}

/** Наступна пара за попередньою: та сама тривалість і перерва 10 хв (найчастіша в коледжі). */
export function nextSlot(slots: readonly SlotDraft[]): SlotDraft {
  const last = slots[slots.length - 1];
  if (!last || !TIME_RE.test(last.endsAt)) return { pair: slots.length + 1, startsAt: '08:30', endsAt: '09:30' };
  const length = duration(last) ?? 60;
  const start = timeToMinutes(last.endsAt) + 10;
  return { pair: slots.length + 1, startsAt: minutesToTime(start), endsAt: minutesToTime(start + length) };
}
