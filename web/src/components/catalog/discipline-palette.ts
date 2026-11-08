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
  { line: 0, hex: '#c5586a', name: 'вишнева' },
  { line: 1, hex: '#c45e39', name: 'теракотова' },
  { line: 2, hex: '#b07100', name: 'гірчична' },
  { line: 3, hex: '#8d8300', name: 'оливкова' },
  { line: 4, hex: '#599234', name: 'травʼяна' },
  { line: 5, hex: '#00996b', name: 'смарагдова' },
  { line: 6, hex: '#009693', name: 'бірюзова' },
  { line: 7, hex: '#0092af', name: 'морська' },
  { line: 8, hex: '#1f86cd', name: 'синя' },
  { line: 9, hex: '#7174d1', name: 'фіалкова' },
  { line: 10, hex: '#9a66be', name: 'бузкова' },
  { line: 11, hex: '#b55b9a', name: 'малинова' },
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
