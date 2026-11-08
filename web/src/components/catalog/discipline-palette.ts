/**
 * Кольори ліній дисциплін для вибору в каталозі. Значення — «крапка» світлої теми з src/styles/lines.css:
 * збережений hex знову дає ту саму лінію (lib/discipline-color шукає найближчий відтінок), а в темній
 * темі лінія бере свій перевірений відповідник, тож контраст AA зберігається в обох темах.
 */
import { disciplineLine, LINE_COUNT } from '@/lib/discipline-color';

export interface LineSwatch {
  line: number;
  hex: string;
  /** Назва для читача екрана й підказки. */
  name: string;
}

export const LINE_SWATCHES: readonly LineSwatch[] = [
  { line: 0, hex: '#737c14', name: 'оливкова' },
  { line: 1, hex: '#008a7f', name: 'бірюзова' },
  { line: 2, hex: '#7466b5', name: 'фіалкова' },
  { line: 3, hex: '#935ba0', name: 'бузкова' },
  { line: 4, hex: '#a8547c', name: 'малинова' },
];

if (LINE_SWATCHES.length !== LINE_COUNT) {
  throw new Error('Палітра ліній у каталозі не збігається з src/styles/lines.css');
}

export const HEX_COLOR_RE = /^#[0-9a-f]{6}$/i;

/** Лінія, яку отримає дисципліна: з кольору каталогу (найближча) або з id, якщо колір не задано. */
export function lineOf(id: string, color: string | null): number {
  return disciplineLine({ id, color: color && HEX_COLOR_RE.test(color) ? color : null });
}

/** Назва лінії для підпису: «синя». */
export function lineName(line: number): string {
  return LINE_SWATCHES[line]?.name ?? '';
}

/** Колір збігається з однією з ліній палітри (тоді в полі вибору підсвічено цю лінію, а не «свій»). */
export function swatchOf(color: string | null): LineSwatch | undefined {
  if (!color) return undefined;
  return LINE_SWATCHES.find((swatch) => swatch.hex.toLowerCase() === color.toLowerCase());
}
