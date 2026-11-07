/** Номери сторінок з «…»: 1 … 4 5 6 … 12 */
export function pageWindow(current: number, count: number): Array<number | 'gap'> {
  if (count <= 7) return Array.from({ length: count }, (_, i) => i);
  const pages = new Set([0, count - 1, current - 1, current, current + 1]);
  if (current <= 2) [1, 2, 3].forEach((p) => pages.add(p));
  if (current >= count - 3) [count - 4, count - 3, count - 2].forEach((p) => pages.add(p));
  const sorted = [...pages].filter((p) => p >= 0 && p < count).sort((a, b) => a - b);
  const out: Array<number | 'gap'> = [];
  sorted.forEach((p, i) => {
    const prev = sorted[i - 1];
    if (prev !== undefined && p - prev > 1) out.push('gap');
    out.push(p);
  });
  return out;
}
