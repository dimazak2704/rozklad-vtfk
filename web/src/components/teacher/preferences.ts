/**
 * Побажання до розкладу як сітка «день × пара»: небажано (мʼяке обмеження генератора) або неможливо
 * (жорстке, DOMAIN §5). Чиста логіка без React — для тестів.
 */
import type { BellSlotDto, PreferenceDto, PreferenceKind, TemplateLessonDto, Weekday } from '@/api/types';
import { countLabel, PAIR_FORMS, PARITY_LABEL, WEEKDAY_ACCUSATIVE } from '@/lib/labels';

export const WEEKDAYS: readonly Weekday[] = [1, 2, 3, 4, 5];

export type CellKey = `${number}:${number}`;
export type PreferenceMap = ReadonlyMap<CellKey, PreferenceKind>;

export const cellKey = (weekday: number, pair: number): CellKey => `${weekday}:${pair}`;

/**
 * «Мої пари» в сітці з базового розкладу семестру: клітинка → «4КІ1 · СТП», а пара лише по чисельниках чи знаменниках —
 * з позначкою тижня («(чисельник)»). Обидві половинки в одній клітинці — без позначки.
 */
export function templateCells(lessons: readonly Pick<TemplateLessonDto, 'weekday' | 'pair' | 'parity' | 'group' | 'subgroup' | 'discipline'>[]): Map<CellKey, string[]> {
  const byCell = new Map<CellKey, Map<string, Set<string>>>();
  for (const lesson of lessons) {
    const key = cellKey(lesson.weekday, lesson.pair);
    const labels = byCell.get(key) ?? new Map<string, Set<string>>();
    const label = `${lesson.group.code}${lesson.subgroup > 0 ? `/${lesson.subgroup}` : ''} · ${lesson.discipline.shortName}`;
    const parities = labels.get(label) ?? new Set<string>();
    if (lesson.parity === 'BOTH') {
      parities.add('NUMERATOR');
      parities.add('DENOMINATOR');
    } else parities.add(lesson.parity);
    labels.set(label, parities);
    byCell.set(key, labels);
  }
  const out = new Map<CellKey, string[]>();
  for (const [key, labels] of byCell) {
    out.set(
      key,
      [...labels].map(([label, parities]) =>
        parities.size === 1 ? `${label} (${PARITY_LABEL[[...parities][0] as 'NUMERATOR' | 'DENOMINATOR']})` : label,
      ),
    );
  }
  return out;
}

/** Клік по клітинці: порожньо → небажано → неможливо → порожньо. */
export function nextKind(kind: PreferenceKind | null | undefined): PreferenceKind | null {
  if (!kind) return 'UNWANTED';
  return kind === 'UNWANTED' ? 'UNAVAILABLE' : null;
}

export function toMap(list: readonly PreferenceDto[]): Map<CellKey, PreferenceKind> {
  return new Map(list.map((p) => [cellKey(p.weekday, p.pair), p.kind]));
}

/** Сітка → тіло PUT (відсортоване; нотатки, якщо були, зберігаємо). */
export function toList(map: PreferenceMap, notes: readonly PreferenceDto[] = []): PreferenceDto[] {
  return [...map.entries()]
    .map(([key, kind]) => {
      const [weekday, pair] = key.split(':').map(Number) as [Weekday, number];
      const note = notes.find((n) => n.weekday === weekday && n.pair === pair)?.note ?? null;
      return { weekday, pair, kind, note };
    })
    .sort((a, b) => a.weekday - b.weekday || a.pair - b.pair);
}

/** Скільки клітинок відрізняються від збереженого стану. */
export function changedCells(saved: PreferenceMap, draft: PreferenceMap): number {
  const keys = new Set<CellKey>([...saved.keys(), ...draft.keys()]);
  let count = 0;
  for (const key of keys) if (saved.get(key) !== draft.get(key)) count += 1;
  return count;
}

export function countKinds(map: PreferenceMap): Record<PreferenceKind, number> {
  const counts: Record<PreferenceKind, number> = { UNWANTED: 0, UNAVAILABLE: 0 };
  for (const kind of map.values()) counts[kind] += 1;
  return counts;
}

export const KIND_TEXT: Record<PreferenceKind, string> = { UNWANTED: 'небажано', UNAVAILABLE: 'неможливо' };

/**
 * Вигляд стану не тримається лише на кольорі: у клітинці завжди є значок (палець униз / заборона) і слово.
 * Тло — суцільний тон без штрихування й рамок: небажано — бурштиновий (як «Заміна»), неможливо — червоний.
 */
export const KIND_CELL: Record<PreferenceKind, string> = {
  UNWANTED: 'bg-change-subtle text-change',
  UNAVAILABLE: 'bg-cancel-subtle text-cancel',
};

/** «У пʼятницю лише 6 пар.» — один раз під сіткою замість «пари немає» в кожній зайвій клітинці. */
export function shorterDaysNote(slotsByWeekday: ReadonlyMap<Weekday, readonly BellSlotDto[]>, maxPairs: number): string | null {
  const byCount = new Map<number, Weekday[]>();
  for (const weekday of WEEKDAYS) {
    const count = slotsByWeekday.get(weekday)?.length ?? 0;
    if (count > 0 && count < maxPairs) byCount.set(count, [...(byCount.get(count) ?? []), weekday]);
  }
  if (byCount.size === 0) return null;
  return [...byCount]
    .map(([count, days]) => {
      const names = days.map((day) => WEEKDAY_ACCUSATIVE[day] ?? '');
      const list = names.length > 1 ? `${names.slice(0, -1).join(', ')} і ${names.at(-1) ?? ''}` : (names[0] ?? '');
      return `У ${list} лише ${countLabel(count, PAIR_FORMS)}.`;
    })
    .join(' ');
}
