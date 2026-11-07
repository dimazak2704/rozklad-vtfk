/**
 * Детермінований колір «лінії» дисципліни. Якщо в каталозі заданий колір, беремо його відтінок
 * і знаходимо найближчу лінію з перевіреної палітри (контраст AA гарантовано в обох темах).
 * Інакше — хеш від id: та сама дисципліна завжди має той самий колір на всіх екранах.
 */
import type { DisciplineRef } from '@/api/types';

/** Відтінки OKLCH для 12 ліній (порядок відповідає data-line у src/styles/lines.css). */
export const LINE_HUES = [12, 40, 75, 105, 135, 165, 192, 215, 245, 280, 310, 340] as const;
export const LINE_COUNT = LINE_HUES.length;

/** FNV-1a 32-bit: стабільний і рівномірний для коротких рядків. */
export function hashString(value: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

function hexToRgb(hex: string): [number, number, number] | null {
  const match = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (!match) return null;
  const raw = match[1] ?? '';
  const full = raw.length === 3 ? [...raw].map((c) => c + c).join('') : raw;
  const n = Number.parseInt(full, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Відтінок OKLCH (у градусах) для sRGB-кольору. Для сірих повертає null. */
export function oklchHue(hex: string): number | null {
  const rgb = hexToRgb(hex);
  if (!rgb) return null;
  const lin = rgb.map((v) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  const l = Math.cbrt(0.4122214708 * lin[0] + 0.5363325363 * lin[1] + 0.0514459929 * lin[2]);
  const m = Math.cbrt(0.2119034982 * lin[0] + 0.6806995451 * lin[1] + 0.1073969566 * lin[2]);
  const s = Math.cbrt(0.0883024619 * lin[0] + 0.2817188376 * lin[1] + 0.6299787005 * lin[2]);
  const a = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const b = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  if (Math.hypot(a, b) < 0.02) return null;
  const hue = (Math.atan2(b, a) * 180) / Math.PI;
  return hue < 0 ? hue + 360 : hue;
}

function nearestLine(hue: number): number {
  let best = 0;
  let bestDistance = Number.POSITIVE_INFINITY;
  LINE_HUES.forEach((lineHue, index) => {
    const raw = Math.abs(lineHue - hue);
    const distance = Math.min(raw, 360 - raw);
    if (distance < bestDistance) {
      best = index;
      bestDistance = distance;
    }
  });
  return best;
}

type DisciplineLike = Pick<DisciplineRef, 'id' | 'color'> & Partial<Pick<DisciplineRef, 'shortName'>>;

/** Індекс лінії 0..11 для дисципліни. */
export function disciplineLine(discipline: DisciplineLike): number {
  const hue = discipline.color ? oklchHue(discipline.color) : null;
  if (hue !== null) return nearestLine(hue);
  const key = discipline.id || discipline.shortName || '';
  return hashString(key) % LINE_COUNT;
}

/** Атрибут для елемента: `<span {...lineAttr(d)} className="bg-[var(--line-dot)]" />`. */
export function lineAttr(discipline: DisciplineLike): { 'data-line': number } {
  return { 'data-line': disciplineLine(discipline) };
}
