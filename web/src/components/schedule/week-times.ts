import type { BellSlotDto, DayDto } from '@/api/types';
import { rowTimes } from '@/lib/schedule';

export interface PairTime {
  startsAt: string;
  endsAt: string;
}

export interface WeekTimes {
  /** Час рядка в колонці часу — той, що в більшості навчальних днів тижня. */
  rows: Map<number, PairTime>;
  /** Час пари в кожному дні (індекс — як у `days`); без дзвінків цього дня — час рядка. */
  byDay: ReadonlyArray<ReadonlyMap<number, PairTime>>;
  /** Дні, де пара йде в інший час, ніж у рядку (пʼятниця: 4 пара о 12:20) — для підпису в колонці часу. */
  exceptions: Map<number, Array<{ weekday: number; time: PairTime }>>;
}

const sameTime = (a: PairTime, b: PairTime) => a.startsAt === b.startsAt && a.endsAt === b.endsAt;

/**
 * Час рядків тижневої сітки за дзвінками кожного дня тижня. Колонка часу одна на всі дні, тож у ній —
 * найчастіший час пари (за рівності — день, що раніше), а дні з іншим часом перелічено окремо. Дзвінків
 * немає (ще вантажаться) — як і раніше, час рядка береться з занять тижня.
 */
export function weekTimes(
  days: ReadonlyArray<Pick<DayDto, 'weekday' | 'studyDay' | 'lessons'>>,
  bells?: ReadonlyMap<number, readonly BellSlotDto[]> | null,
): WeekTimes {
  const dayBells = days.map((day) =>
    day.studyDay && bells?.has(day.weekday) ? new Map((bells.get(day.weekday) ?? []).map((slot) => [slot.pair, slot])) : null,
  );

  // найчастіший час кожної пари серед днів, для яких дзвінки відомі
  const counts = new Map<number, Array<{ time: PairTime; count: number }>>();
  for (const perDay of dayBells) {
    for (const [pair, { startsAt, endsAt }] of perDay ?? []) {
      const options = counts.get(pair) ?? [];
      const known = options.find((option) => sameTime(option.time, { startsAt, endsAt }));
      if (known) known.count += 1;
      else options.push({ time: { startsAt, endsAt }, count: 1 });
      counts.set(pair, options);
    }
  }
  const rows = rowTimes(days);
  for (const [pair, options] of counts) {
    // strict «>»: за рівності лишається час дня, що раніше в тижні
    let best = options[0];
    for (const option of options) if (best && option.count > best.count) best = option;
    if (best) rows.set(pair, best.time);
  }

  const byDay = dayBells.map((perDay) => {
    const times = new Map(rows);
    for (const [pair, { startsAt, endsAt }] of perDay ?? []) times.set(pair, { startsAt, endsAt });
    return times;
  });

  const exceptions = new Map<number, Array<{ weekday: number; time: PairTime }>>();
  days.forEach((day, index) => {
    for (const [pair, { startsAt, endsAt }] of dayBells[index] ?? []) {
      const row = rows.get(pair);
      if (!row || sameTime(row, { startsAt, endsAt })) continue;
      exceptions.set(pair, [...(exceptions.get(pair) ?? []), { weekday: day.weekday, time: { startsAt, endsAt } }]);
    }
  });

  return { rows, byDay, exceptions };
}
