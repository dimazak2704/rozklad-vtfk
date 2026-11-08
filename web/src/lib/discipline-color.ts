/**
 * Колір «лінії» дисципліни. Ліній лише 5 (src/styles/lines.css), і всі далеко від кольорів станів.
 * У сітці розкладу кольорів немає — лінія потрібна лише картці деталей заняття й діаграмам адмінки.
 *
 * Два способи:
 *  - `assignLines(disciplines)` — для одного екрана: дисципліни отримують лінії з найбільшою відстанню
 *    відтінків, без збігів у межах п'яти; шоста й далі — нейтральні (-1 → --fill-muted).
 *  - `disciplineLine(d)` — без контексту екрана: колір з каталогу → найближча лінія, інакше хеш від id.
 */
import type { DisciplineRef } from '@/api/types';

/** Відтінки OKLCH для 5 ліній (порядок відповідає data-line у src/styles/lines.css). */
export const LINE_HUES = [115, 186, 290, 320, 352] as const;
export const LINE_COUNT = LINE_HUES.length;

/** Порядок видачі ліній на екрані: кожна наступна якнайдалі від уже виданих (115 → 290 → 186 → 352 → 320). */
const SPREAD_ORDER = [0, 2, 1, 4, 3] as const;

/** Лінія «без кольору» — нейтральна крапка. */
export const NEUTRAL_LINE = -1;

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

function hueDistance(a: number, b: number): number {
  const raw = Math.abs(a - b);
  return Math.min(raw, 360 - raw);
}

function nearestLine(hue: number, among: readonly number[] = SPREAD_ORDER): number {
  let best = among[0] ?? 0;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const index of among) {
    const distance = hueDistance(LINE_HUES[index] ?? 0, hue);
    if (distance < bestDistance) {
      best = index;
      bestDistance = distance;
    }
  }
  return best;
}

type DisciplineLike = Pick<DisciplineRef, 'id' | 'color'> & Partial<Pick<DisciplineRef, 'shortName'>>;

/** Індекс лінії 0..4 для дисципліни без контексту екрана. */
export function disciplineLine(discipline: DisciplineLike): number {
  const hue = discipline.color ? oklchHue(discipline.color) : null;
  if (hue !== null) return nearestLine(hue);
  const key = discipline.id || discipline.shortName || '';
  return hashString(key) % LINE_COUNT;
}

/**
 * Лінії для всіх дисциплін одного екрана: перші п'ять різних дисциплін отримують різні лінії
 * (колір каталогу — найближча ще вільна, інакше наступна з SPREAD_ORDER), решта — NEUTRAL_LINE.
 * Порядок стабільний: за id, тож той самий розклад завжди фарбується однаково.
 */
export function assignLines(disciplines: Iterable<DisciplineLike>): Map<string, number> {
  const unique = new Map<string, DisciplineLike>();
  for (const discipline of disciplines) {
    if (discipline.id && !unique.has(discipline.id)) unique.set(discipline.id, discipline);
  }
  const ordered = [...unique.values()].sort((a, b) => a.id.localeCompare(b.id));
  const free: number[] = [...SPREAD_ORDER];
  const result = new Map<string, number>();
  // спершу ті, хто має колір у каталозі: їм — найближча вільна лінія
  for (const discipline of ordered) {
    const hue = discipline.color ? oklchHue(discipline.color) : null;
    if (hue === null || free.length === 0) continue;
    const line = nearestLine(hue, free);
    result.set(discipline.id, line);
    free.splice(free.indexOf(line), 1);
  }
  for (const discipline of ordered) {
    if (result.has(discipline.id)) continue;
    result.set(discipline.id, free.shift() ?? NEUTRAL_LINE);
  }
  return result;
}

/**
 * Атрибут для елемента: `<span {...lineAttr(d)} className="bg-[var(--line-dot)]" />`.
 * Другим аргументом можна передати карту з `assignLines`, щоб кольори на екрані не повторювались.
 */
export function lineAttr(discipline: DisciplineLike, lines?: ReadonlyMap<string, number>): { 'data-line': number } {
  return { 'data-line': lines?.get(discipline.id) ?? disciplineLine(discipline) };
}
