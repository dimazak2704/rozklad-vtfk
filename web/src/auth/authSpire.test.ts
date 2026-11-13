// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Файли читаємо як текст (типів node у src немає, тож fs беремо динамічно, як у authPageCss.test.ts)
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
};
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const css = read('../styles/auth-page.css');
const loginHtml = read('../../../services/auth/src/main/resources/templates/login.html');

type Point = readonly [number, number];

/** Шляхи з <defs> великого знака: id → { d, pathLength }. */
const paths = new Map(
  [...loginHtml.matchAll(/<path id="(auth-spire-[\w-]+)" pathLength="([\d.]+)"\s+d="([^"]+)"/g)].map((match) => [
    match[1] ?? '',
    { length: Number(match[2]), d: match[3] ?? '' },
  ]),
);

function pathOf(id: string) {
  const path = paths.get(id);
  if (!path) throw new Error(`немає шляху ${id}`);
  return path;
}

/** --len із правила, що починається селектором `selector` (перше входження). */
function lenOf(selector: string): number {
  const match = new RegExp(`${selector.replace('.', '\\.')}[^{]*\\{\\s*--len: ([\\d.]+);`).exec(css);
  if (!match) throw new Error(`немає --len для ${selector}`);
  return Number(match[1]);
}

const edge = Number(/--edge: ([\d.]+);/.exec(css)?.[1]);

/**
 * Точки вздовж шляху з командами M, A (лише кола), C (з неявним повтором) і Z — тих, що є в знаку.
 * Крок дрібний, тож довжина ламаної збігається зі справжньою до сотих.
 */
function sample(d: string): Point[] {
  const tokens = d.match(/[MACZ]|-?\d*\.?\d+/g) ?? [];
  const points: Point[] = [];
  let i = 0;
  let command = '';
  let current: Point = [0, 0];
  let start: Point = [0, 0];
  const num = () => Number(tokens[i++]);
  const steps = 2000;
  while (i < tokens.length) {
    if (/[MACZ]/.test(tokens[i] ?? '')) command = tokens[i++] ?? '';
    if (command === 'M') {
      current = [num(), num()];
      start = current;
      points.push(current);
    } else if (command === 'C') {
      const c1: Point = [num(), num()];
      const c2: Point = [num(), num()];
      const end: Point = [num(), num()];
      const from = current;
      for (let s = 1; s <= steps; s++) {
        const t = s / steps;
        const u = 1 - t;
        const at = (k: 0 | 1) => u ** 3 * from[k] + 3 * u * u * t * c1[k] + 3 * u * t * t * c2[k] + t ** 3 * end[k];
        points.push([at(0), at(1)]);
      }
      current = end;
    } else if (command === 'A') {
      const r = num();
      num(); // ry = rx: у знаку лише кола
      num(); // поворот
      const large = num();
      const sweep = num();
      const end: Point = [num(), num()];
      // центр кола за SVG 1.1, F.6.5 (rx = ry, без повороту)
      const mx = (current[0] - end[0]) / 2;
      const my = (current[1] - end[1]) / 2;
      const factor = Math.sqrt(Math.max(0, (r * r - mx * mx - my * my) / (mx * mx + my * my)));
      const sign = large === sweep ? -1 : 1;
      const cx = (current[0] + end[0]) / 2 + sign * factor * my;
      const cy = (current[1] + end[1]) / 2 - sign * factor * mx;
      const a0 = Math.atan2(current[1] - cy, current[0] - cx);
      let delta = Math.atan2(end[1] - cy, end[0] - cx) - a0;
      if (sweep === 1 && delta < 0) delta += 2 * Math.PI;
      if (sweep === 0 && delta > 0) delta -= 2 * Math.PI;
      if (Math.abs(delta) < 1e-6) delta = sweep === 1 ? 2 * Math.PI : -2 * Math.PI;
      for (let s = 1; s <= steps; s++) {
        const angle = a0 + (delta * s) / steps;
        points.push([cx + r * Math.cos(angle), cy + r * Math.sin(angle)]);
      }
      current = end;
    } else if (command === 'Z') {
      points.push(start);
      current = start;
      command = '';
    } else {
      throw new Error(`невідома команда в ${d}`);
    }
  }
  return points;
}

const lengthOf = (points: readonly Point[]) =>
  points.slice(1).reduce((sum, point, index) => sum + Math.hypot(point[0] - points[index]![0], point[1] - points[index]![1]), 0);

/** Відстань уздовж шляху до найближчої до `target` точки і сама відстань до неї. */
function locate(points: readonly Point[], target: Point) {
  let along = 0;
  let best = { along: 0, miss: Infinity };
  points.forEach((point, index) => {
    if (index > 0) along += Math.hypot(point[0] - points[index - 1]![0], point[1] - points[index - 1]![1]);
    const miss = Math.hypot(point[0] - target[0], point[1] - target[1]);
    if (miss < best.miss) best = { along, miss };
  });
  return best;
}

/**
 * Ключові кадри @keyframes name: [відсоток, --auth-trace-head]. Перший кадр тримає голову до останньої
 * своєї зупинки («0%, 48.25%»), другий діє з першої («92%, 100%»), тож беремо саме ці відсотки.
 */
function keyframes(name: string): [number, number][] {
  const body = new RegExp(`@keyframes ${name} \\{([\\s\\S]*?)\\n\\}`).exec(css)?.[1] ?? '';
  return [...body.matchAll(/((?:[\d.]+%,?\s*)+)\{\s*--auth-trace-head: (-?[\d.]+);/g)].map((match) => {
    const stops = [...(match[1] ?? '').matchAll(/([\d.]+)%/g)].map((stop) => Number(stop[1]));
    return { stops, head: Number(match[2]) };
  }).map(({ stops, head }, index) => [(index === 0 ? stops.at(-1) : stops[0]) ?? NaN, head]);
}

const outer = sample(pathOf('auth-spire-outer').d);
const tower = sample(pathOf('auth-spire-tower').d);
const towerRun = sample(pathOf('auth-spire-tower-run').d);
const innerRun = sample(pathOf('auth-spire-inner-run').d);
const inner = sample(pathOf('auth-spire-inner').d);
const offScreen = ([x, y]: Point) => x > edge || y > edge;

/**
 * Великий знак сторінки входу (login.html + auth-page.css): частина за краєм екрана, світло народжується
 * й гасне там, де його не видно, а таймінг світла узгоджений із довжинами фігур.
 */
describe('великий знак на сторінці входу', () => {
  it('pathLength кожної фігури — її справжня довжина, і CSS рахує штрихи з тих самих чисел', () => {
    expect([...paths.keys()].sort()).toEqual([
      'auth-spire-inner',
      'auth-spire-inner-run',
      'auth-spire-outer',
      'auth-spire-tower',
      'auth-spire-tower-run',
    ]);
    for (const [id, { length, d }] of paths) {
      expect(lengthOf(sample(d)), id).toBeCloseTo(length, 1);
    }
    expect(lenOf('.auth-spire-outer')).toBe(pathOf('auth-spire-outer').length);
    expect(lenOf('.auth-spire-tower')).toBe(pathOf('auth-spire-tower').length);
    expect(lenOf('.auth-spire-inner')).toBe(pathOf('auth-spire-inner').length);
    expect(lenOf('.auth-trace-tower')).toBe(pathOf('auth-spire-tower-run').length);
    expect(lenOf('.auth-trace-inner')).toBe(pathOf('auth-spire-inner-run').length);
    // кожна група світла біжить по своєму шляху
    for (const [group, id] of [
      ['auth-trace-outer', 'auth-spire-outer'],
      ['auth-trace-tower', 'auth-spire-tower-run'],
      ['auth-trace-inner', 'auth-spire-inner-run'],
    ] as const) {
      const block = new RegExp(`<g class="auth-trace ${group}">([\\s\\S]*?)</g>`).exec(loginHtml)?.[1] ?? '';
      const hrefs = [...block.matchAll(/href="#([\w-]+)"/g)].map((match) => match[1]);
      expect(hrefs, group).toEqual([id, id, id, id]);
    }
  });

  it('видно близько 3/4 знака: низ кільця й основа шпиля за екраном, а в самому куті не лишається клаптика кільця', () => {
    const r = 11.6;
    expect(edge).toBeGreaterThan(13 + 0.6 * r);
    // кут екрана ближче до центру, ніж кільце: кільце проходить за кутом, а не торкається його
    expect(Math.SQRT2 * (edge - 13)).toBeLessThan(r - 0.5);
    // кути основи шпиля за краєм
    expect(edge).toBeLessThan(21.6 - 0.5);
  });

  it('кожен біг світла починається й закінчується за краєм екрана або на вже освітленій лінії', () => {
    // кільце й шпиль починаються й закінчуються за екраном
    for (const run of [outer, towerRun]) {
      expect(offScreen(run[0]!)).toBe(true);
      expect(offScreen(run.at(-1)!)).toBe(true);
    }
    // контур шпиля малюється з тієї ж прихованої точки, з якої біжить світло
    expect(tower[0]).toEqual(towerRun[0]);
    // мале кільце відгалужується від лівої ноги шпиля і вливається в праву
    const fork = locate(towerRun, innerRun[0]!);
    const merge = locate(towerRun, innerRun.at(-1)!);
    expect(fork.miss).toBeLessThan(0.02);
    expect(merge.miss).toBeLessThan(0.02);
    expect(fork.along).toBeLessThan(lengthOf(towerRun) / 2);
    expect(merge.along).toBeGreaterThan(lengthOf(towerRun) / 2);
    // контур малого кільця теж починається в точці розгалуження й росте лише тоді, коли туди дійшов шпиль
    expect(inner[0]).toEqual(innerRun[0]);
    const from = Number(/\.auth-spire-line \.auth-spire-inner \{\s*--from: ([\d.]+);/.exec(css)?.[1]);
    expect(from).toBeCloseTo((fork.along + 1) / (pathOf('auth-spire-tower').length + 1), 2);
  });

  it('таймінг: стала швидкість, шпиль стартує з кінцем кільця, мале кільце — коли голова шпиля в точці розгалуження', () => {
    const lo = pathOf('auth-spire-outer').length;
    const lt = pathOf('auth-spire-tower-run').length;
    const li = pathOf('auth-spire-inner-run').length;
    const forkAt = locate(towerRun, innerRun[0]!).along;
    const outerFrames = keyframes('auth-trace-outer');
    const towerFrames = keyframes('auth-trace-tower');
    const innerFrames = keyframes('auth-trace-inner');
    // голова від −1 до 1,75 × довжини + 1: хвіст 3/4 фігури повністю зійшов
    expect(outerFrames.map(([, head]) => head)).toEqual([-1, 128.54]);
    expect(towerFrames.map(([, head]) => head)).toEqual([-1, 65.98]);
    expect(innerFrames.map(([, head]) => head)).toEqual([-1, 51.49]);
    expect(outerFrames[1]![1]).toBeCloseTo(1.75 * lo + 1, 1);
    expect(towerFrames[1]![1]).toBeCloseTo(1.75 * lt + 1, 1);
    expect(innerFrames[1]![1]).toBeCloseTo(1.75 * li + 1, 1);

    // відсотків циклу на одиницю шляху — однаково для всіх фігур
    const towerStart = lo + 1;
    const rate = towerFrames[1]![0] / (towerStart + 1.75 * lt + 2);
    expect(outerFrames[0]![0]).toBe(0);
    expect(css).toMatch(/@keyframes auth-trace-outer \{\s*0% \{/);
    expect(outerFrames[1]![0]).toBeCloseTo((1.75 * lo + 2) * rate, 1);
    expect(towerFrames[0]![0]).toBeCloseTo(towerStart * rate, 1);
    expect(innerFrames[0]![0]).toBeCloseTo((towerStart + forkAt) * rate, 1);
    expect(innerFrames[1]![0]).toBeCloseTo((towerStart + forkAt + 1.75 * li + 2) * rate, 1);
    // мале кільце гасне раніше за шпиль, а після шпиля лишається пауза
    expect(innerFrames[1]![0]).toBeLessThan(towerFrames[1]![0]);
    expect(towerFrames[1]![0]).toBeLessThanOrEqual(95);
  });

  it('без руху й у примусових кольорах світла немає, лише статичний контур', () => {
    const motion = css.slice(css.indexOf('@media (prefers-reduced-motion: no-preference)'));
    const beforeMotion = css.slice(0, css.indexOf('@media (prefers-reduced-motion: no-preference)'));
    expect(beforeMotion).toMatch(/\.auth-trace \{\s*display: none;/);
    expect(beforeMotion).not.toMatch(/animation/);
    expect(motion).toMatch(/\.auth-trace \{\s*display: inline;/);
    const forced = css.slice(css.indexOf('@media (forced-colors: active)'));
    expect(forced).toMatch(/\.auth-trace \{\s*display: none;/);
  });
});
