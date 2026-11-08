/**
 * Побажання до розкладу як сітка «день × пара»: небажано (мʼяке обмеження генератора) або неможливо
 * (жорстке, DOMAIN §5). Чиста логіка без React — для тестів.
 */
import type { PreferenceDto, PreferenceKind, Weekday } from '@/api/types';

export type CellKey = `${number}:${number}`;
export type PreferenceMap = ReadonlyMap<CellKey, PreferenceKind>;

export const cellKey = (weekday: number, pair: number): CellKey => `${weekday}:${pair}`;

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

/** Вигляд стану не тримається лише на кольорі: пунктир — небажано, штрихування — неможливо. */
export const KIND_CELL: Record<PreferenceKind, string> = {
  UNWANTED: 'border-dashed border-strong bg-sunken text-secondary',
  UNAVAILABLE: 'hatch border-2 border-strong bg-block-subtle text-primary [--hatch-color:var(--border-color-strong)]',
};
