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

/** <use> світла у групі `auth-trace <group>`: класи й ціль. */
function traceUses(group: string) {
  // група закривається на своєму відступі: вкладені <g> (ділянки шпиля) усередині не обривають її
  const block = new RegExp(`<g class="auth-trace ${group}">([\\s\\S]*?)\\n  </g>`).exec(loginHtml)?.[1] ?? '';
  return [...block.matchAll(/<use class="([^"]+)" href="#([\w-]+)"\/>/g)].map((match) => ({
    classes: (match[1] ?? '').split(' '),
    href: match[2] ?? '',
  }));
}

/** Тіло правила, селектор якого (повністю) — `selector`. */
function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // саме правило з цим селектором, а не продовження списку («.a,\n.b {»)
  const match = new RegExp(`(?<!,)\\n${escaped} \\{([\\s\\S]*?)\\n\\}`).exec(css);
  if (!match) throw new Error(`немає правила ${selector}`);
  return match[1] ?? '';
}

const prop = (body: string, name: string) => {
  const match = new RegExp(`${name}: ([^;]+);`).exec(body);
  if (!match) throw new Error(`немає ${name}`);
  return match[1] ?? '';
};

const propOr = (body: string, name: string, fallback: string) => (body.includes(`${name}:`) ? prop(body, name) : fallback);

/** Позиції вздовж шляху (накопичена довжина) для точок sample(). */
function alongOf(points: readonly Point[]) {
  let along = 0;
  return points.map((point, index) => {
    if (index > 0) along += Math.hypot(point[0] - points[index - 1]![0], point[1] - points[index - 1]![1]);
    return along;
  });
}

type Rect = [number, number, number, number];
type MaskLayers = { rect: Rect; op: string }[];

/**
 * Шари маски копії шпиля (без першого — градієнта яскравості): прямокутник [x, y, ширина, висота]
 * і оператор, яким шар лягає на нижчі (mask-composite; перший оператор — для градієнта).
 */
function towerLayers(name: 'left' | 'right' | 'base'): MaskLayers {
  const shared = rule('.auth-trace-tower use');
  const body = rule(`.auth-trace-tower .auth-trace-${name}`);
  const list = (value: string) => value.split(', ');
  const sizes = list(propOr(body, 'mask-size', prop(shared, 'mask-size')));
  const positions = list(propOr(body, 'mask-position', propOr(shared, 'mask-position', '0 0')));
  const ops = list(propOr(body, 'mask-composite', prop(shared, 'mask-composite')));
  expect(sizes[0], `${name}: градієнт на весь viewBox`).toBe('26px 26px');
  expect(positions[0]).toBe('0 0');
  expect(ops[0], `${name}: градієнт лише вирізає ділянку`).toBe('intersect');
  expect(positions).toHaveLength(sizes.length);
  expect(ops).toHaveLength(sizes.length - 1);
  return sizes.slice(1).map((size, i) => {
    const [w, h] = size.split(' ').map((v) => parseFloat(v));
    const [x, y] = positions[i + 1]!.split(' ').map((v) => parseFloat(v));
    // оператор шару з mask-composite; найнижчий шар ні на що не лягає
    return { rect: [x!, y!, w!, h!] as Rect, op: ops[i + 1] ?? 'add' };
  });
}

/** Маска копії (без градієнта): шари знизу вгору, кожен своїм оператором лягає на нижчі (mask-composite). */
function composeMask(layers: MaskLayers, cover: (rect: Rect) => number): number {
  return layers.reduceRight((below, { rect, op }, index) => {
    const s = cover(rect);
    if (index === layers.length - 1) return s;
    if (op === 'add') return s + below * (1 - s);
    if (op === 'subtract') return s * (1 - below);
    if (op === 'intersect') return s * below;
    throw new Error(`невідомий оператор ${op}`);
  }, 0);
}

const outer = sample(pathOf('auth-spire-outer').d);
const tower = sample(pathOf('auth-spire-tower').d);
const inner = sample(pathOf('auth-spire-inner').d);
const offScreen = ([x, y]: Point) => x > edge || y > edge;
const samePoint = (a: Point, b: Point) => Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.002;

/**
 * Великий знак сторінки входу (login.html + auth-page.css): частина за краєм екрана, світло народжується
 * й гасне там, де його не видно, а таймінг світла узгоджений із довжинами фігур.
 */
describe('великий знак на сторінці входу', () => {
  it('pathLength кожної фігури — її справжня довжина, і CSS рахує штрихи з тих самих чисел', () => {
    expect([...paths.keys()].sort()).toEqual(['auth-spire-inner', 'auth-spire-outer', 'auth-spire-tower']);
    for (const [id, { length, d }] of paths) {
      expect(lengthOf(sample(d)), id).toBeCloseTo(length, 1);
    }
    expect(lenOf('.auth-spire-outer')).toBe(pathOf('auth-spire-outer').length);
    expect(lenOf('.auth-spire-tower')).toBe(pathOf('auth-spire-tower').length);
    expect(lenOf('.auth-spire-inner')).toBe(pathOf('auth-spire-inner').length);
    expect(lenOf('.auth-trace-tower')).toBe(pathOf('auth-spire-tower').length);
    expect(lenOf('.auth-trace-inner')).toBe(pathOf('auth-spire-inner').length);
    // світло біжить по тих самих шляхах, що й контур: лежить точно на лініях, а не поруч
    const line = /<g class="auth-spire-line">([\s\S]*?)<\/g>/.exec(loginHtml)?.[1] ?? '';
    expect([...line.matchAll(/href="#([\w-]+)"/g)].map((match) => match[1])).toEqual([
      'auth-spire-outer',
      'auth-spire-tower',
      'auth-spire-inner',
    ]);
    for (const [group, id] of [
      ['auth-trace-outer', 'auth-spire-outer'],
      ['auth-trace-tower', 'auth-spire-tower'],
      ['auth-trace-inner', 'auth-spire-inner'],
    ] as const) {
      const hrefs = traceUses(group).map((use) => use.href);
      expect(hrefs.length, group).toBeGreaterThan(0);
      expect(new Set(hrefs), group).toEqual(new Set([id]));
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

  it('мале кільце — справжнє коло (центр і радіус як у малому знаку), повний оберт за годинниковою стрілкою', () => {
    // кола малого знака: зовнішнє (13; 13; 11,6) і внутрішнє — його й повторює мале кільце шпиля
    const circles = [...loginHtml.matchAll(/<circle class="auth-stroke" pathLength="1" cx="13" cy="([\d.]+)" r="([\d.]+)"\/>/g)];
    const [cy, r] = [Number(circles[1]?.[1]), Number(circles[1]?.[2])];
    expect([cy, r]).toEqual([8.9, 5.2]);
    // кожна точка шляху лежить на колі: жодна дуга не з'їжджає з центру
    const worst = Math.max(...inner.map(([x, y]) => Math.abs(Math.hypot(x - 13, y - cy) - r)));
    expect(worst).toBeLessThan(0.002);
    expect(lengthOf(inner)).toBeCloseTo(2 * Math.PI * r, 1);
    expect(samePoint(inner[0]!, inner.at(-1)!)).toBe(true);
    // за годинниковою стрілкою на екрані (вісь y донизу): площа за формулою шнурка додатна
    const area = inner.slice(1).reduce((sum, [x, y], index) => sum + inner[index]![0] * y - x * inner[index]![1], 0);
    expect(area).toBeGreaterThan(0);
    // проходить і нижню дугу, що всередині шпиля
    expect(Math.max(...inner.map(([, y]) => y))).toBeCloseTo(cy + r, 2);
  });

  it('кожен біг світла починається й закінчується за краєм екрана або на вже освітленій лінії', () => {
    // кільце й шпиль починаються й закінчуються за екраном
    for (const run of [outer, tower]) {
      expect(offScreen(run[0]!)).toBe(true);
      expect(offScreen(run.at(-1)!)).toBe(true);
    }
    // шпиль замкнений: після ніг світло проходить і основу, видиму над краєм екрана
    expect(samePoint(tower[0]!, tower.at(-1)!)).toBe(true);
    const rightFoot = locate(tower, [18.8, 21.6]);
    expect(rightFoot.miss).toBeLessThan(0.002);
    let along = 0;
    const base = tower.filter((point, index) => {
      if (index > 0) along += Math.hypot(point[0] - tower[index - 1]![0], point[1] - tower[index - 1]![1]);
      return along > rightFoot.along + 0.01;
    });
    expect(base.some((point) => !offScreen(point))).toBe(true);
    // мале кільце відгалужується від лівої ноги шпиля і після повного оберту повертається в ту ж точку
    const fork = locate(tower, inner[0]!);
    expect(fork.miss).toBeLessThan(0.02);
    expect(fork.along).toBeLessThan(rightFoot.along / 2);
    // контур малого кільця росте лише тоді, коли туди дійшов контур шпиля
    const from = Number(/\.auth-spire-line \.auth-spire-inner \{\s*--from: ([\d.]+);/.exec(css)?.[1]);
    expect(from).toBeCloseTo((fork.along + 1) / (pathOf('auth-spire-tower').length + 1), 2);
  });

  it('таймінг: стала швидкість, шпиль стартує з кінцем кільця, мале кільце — коли голова шпиля в точці розгалуження', () => {
    const lo = pathOf('auth-spire-outer').length;
    const lt = pathOf('auth-spire-tower').length;
    const li = pathOf('auth-spire-inner').length;
    const forkAt = locate(tower, inner[0]!).along;
    const outerFrames = keyframes('auth-trace-outer');
    const towerFrames = keyframes('auth-trace-tower');
    const innerFrames = keyframes('auth-trace-inner');
    // голова від −1 до 1,75 × довжини + 1: хвіст 3/4 фігури повністю зійшов
    expect(outerFrames.map(([, head]) => head)).toEqual([-1, 128.54]);
    expect(towerFrames.map(([, head]) => head)).toEqual([-1, 87.28]);
    expect(innerFrames.map(([, head]) => head)).toEqual([-1, 58.17]);
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

  it('світло — один суцільний штрих на фігуру (хвіст і ширша голова з тим самим пунктиром), без шарів-рисок', () => {
    for (const group of ['auth-trace-outer', 'auth-trace-inner']) {
      expect(traceUses(group).map((use) => use.classes), group).toEqual([['auth-trace-tail'], ['auth-trace-head']]);
    }
    // шпиль: по копії хвоста й голови на кожну криву, маски копій ділять знак без перекриття
    expect(traceUses('auth-trace-tower').map((use) => use.classes.join(' '))).toEqual([
      'auth-trace-tail auth-trace-left',
      'auth-trace-tail auth-trace-right',
      'auth-trace-tail auth-trace-base',
      'auth-trace-head auth-trace-left',
      'auth-trace-head auth-trace-right',
      'auth-trace-head auth-trace-base',
    ]);
    // старих шарів зі своїми довжинами рисок і прозорістю немає
    expect(css).not.toMatch(/auth-trace-(fade|body)/);
    expect(loginHtml).not.toMatch(/auth-trace-(fade|body)/);
    const traceStroke = css.slice(css.indexOf('.auth-trace {'), css.indexOf('/* Знак заходить'));
    expect(traceStroke.match(/stroke-dasharray/g)).toHaveLength(1);
    expect(traceStroke).not.toMatch(/stroke-opacity/);
    // одна риска завдовжки хвіст (3/4 фігури), спільна для всіх копій
    const use = rule('.auth-trace use');
    expect(prop(use, 'stroke-dasharray')).toBe('calc(var(--len) * 0.75 * 1px) calc((var(--len) * 1.25 + 4) * 1px)');
    expect(prop(use, 'stroke-dashoffset')).toBe('calc((var(--len) * 0.75 - var(--auth-trace-head)) * 1px)');
    expect(prop(use, 'mask-origin')).toBe('view-box');
    const tail = Number(prop(rule('.auth-trace-tail'), '--w'));
    const head = Number(prop(rule('.auth-trace-head'), '--w'));
    expect(head).toBeGreaterThan(tail);
  });

  it('яскравість спадає плавно: точки градієнта йдуть по кривій (1 − u)^1,5 для хвоста й (1 − u)^2 для голови', () => {
    const alpha = (name: string) =>
      Number(new RegExp(`${name}: color-mix\\(in srgb, black ([\\d.]+)%, transparent\\);`).exec(css)?.[1]) / 100;
    const tails = [90, 80, 70, 60, 50, 40, 30, 20, 15, 10, 5];
    const heads = [875, 750, 625, 500, 375, 250, 125];
    // від кінця хвоста до голови яскравість лише зростає, а сусідні точки близькі
    const tailRamp = [0, ...tails.map((n) => alpha(`--tail-${n}`)), 1];
    const headRamp = [0, ...heads.map((n) => alpha(`--head-${n}`)), 1];
    // (спад голови — 3,5 одиниці, тож її точки й так стоять тісно)
    for (const [ramp, step] of [
      [tailRamp, 0.15],
      [headRamp, 0.25],
    ] as const) {
      ramp.slice(1).forEach((value, index) => {
        expect(value).toBeGreaterThan(ramp[index]!);
        expect(value - ramp[index]!).toBeLessThan(step);
      });
    }
    tails.forEach((n) => expect(alpha(`--tail-${n}`)).toBeCloseTo((1 - n / 100) ** 1.5, 3));
    heads.forEach((n) => expect(alpha(`--head-${n}`)).toBeCloseTo((1 - n / 1000) ** 2, 3));

    // кільця: позиції в конусі (від голови назад) відповідають тим самим частками хвоста
    const conicTail = rule('.auth-trace-outer .auth-trace-tail,\n.auth-trace-inner .auth-trace-tail');
    const conicTailStops = [...conicTail.matchAll(/var\(--tail-(\d+)\) ([\d.]+)deg/g)];
    expect(conicTailStops.map((match) => Number(match[1]))).toEqual(tails);
    conicTailStops.forEach((match) => expect(Number(match[2])).toBeCloseTo(360 - 270 * (Number(match[1]) / 100), 6));
    expect(conicTail).toMatch(/transparent 0 90deg,/);
    expect(conicTail).toMatch(/black 360deg/);
    const conicHead = rule('.auth-trace-outer .auth-trace-head,\n.auth-trace-inner .auth-trace-head');
    const conicHeadStops = [...conicHead.matchAll(/var\(--head-(\d+)\) calc\(360deg - ([\d.]+)deg \/ var\(--len\)\)/g)];
    expect(conicHeadStops.map((match) => Number(match[1]))).toEqual(heads);
    conicHeadStops.forEach((match) => expect(Number(match[2])).toBeCloseTo((3.5 * Number(match[1])) / 1000 * 360, 6));

    // шпиль: ті самі частки, лише в одиницях шляху
    const linearTail = rule('.auth-trace-tower .auth-trace-tail');
    const linearTailStops = [...linearTail.matchAll(/var\(--tail-(\d+)\) calc\(\(var\(--a\) \+ \(var\(--auth-trace-head\) - var\(--len\) \* ([\d.]+)\)/g)];
    expect(linearTailStops.map((match) => Number(match[1]))).toEqual(tails);
    linearTailStops.forEach((match) => expect(Number(match[2])).toBeCloseTo(0.75 * (Number(match[1]) / 100), 6));
    const linearHead = rule('.auth-trace-tower .auth-trace-head');
    const linearHeadStops = [...linearHead.matchAll(/var\(--head-(\d+)\) calc\(\(var\(--a\) \+ \(var\(--auth-trace-head\) - ([\d.]+)\)/g)];
    expect(linearHeadStops.map((match) => Number(match[1]))).toEqual(heads);
    linearHeadStops.forEach((match) => expect(Number(match[2])).toBeCloseTo((3.5 * Number(match[1])) / 1000, 6));
  });

  it('конус кілець стоїть у центрі кола й повертається з головою від точки початку шляху', () => {
    for (const [group, points, cx, cy] of [
      ['.auth-trace-outer', outer, 13, 13],
      ['.auth-trace-inner', inner, 13, 8.9],
    ] as const) {
      const body = rule(group);
      expect(prop(body, '--cx')).toBe(`${cx}px`);
      expect(prop(body, '--cy')).toBe(`${cy}px`);
      // кут CSS: від верху за годинниковою стрілкою (вісь y донизу)
      const [x, y] = points[0]!;
      const start = ((Math.atan2(x - cx, -(y - cy)) * 180) / Math.PI + 360) % 360;
      expect(Number(prop(body, '--start').replace('deg', ''))).toBeCloseTo(start, 2);
    }
    expect(prop(rule('.auth-trace-outer use,\n.auth-trace-inner use'), '--from')).toBe(
      'calc(var(--start) + var(--auth-trace-head) / var(--len) * 360deg)',
    );
  });

  it('градієнти шпиля відображають позицію на кожній кривій у маску точно, а ділянки ділять знак без перекриття', () => {
    const along = alongOf(tower);
    const apex = locate(tower, [13, 4.2]).along;
    const rightFoot = locate(tower, [18.8, 21.6]).along;
    const regions = (['left', 'right', 'base'] as const).map((name) => {
      const body = rule(`.auth-trace-tower .auth-trace-${name}`);
      const [from, to] = name === 'left' ? [0, apex] : name === 'right' ? [apex, rightFoot] : [rightFoot, along.at(-1)!];
      return {
        name,
        dir: parseFloat(prop(body, '--dir')),
        a: Number(prop(body, '--a')),
        k: Number(prop(body, '--k')),
        layers: towerLayers(name),
        from,
        to,
      };
    });
    // маска ділянки в точці (прямокутники з різким краєм: 1 усередині, 0 поза)
    const coverage = ([x, y]: Point, layers: MaskLayers) =>
      composeMask(layers, ([rx, ry, w, h]) => (x > rx && x < rx + w && y > ry && y < ry + h ? 1 : 0));

    // ділянки разом вкривають увесь viewBox рівно один раз
    for (let gx = 0.05; gx < 26; gx += 0.25) {
      for (let gy = 0.05; gy < 26; gy += 0.25) {
        expect(regions.map(({ layers }) => coverage([gx, gy], layers)).sort()).toEqual([0, 0, 1]);
      }
    }

    for (const region of regions) {
      const angle = (region.dir * Math.PI) / 180;
      const dir: Point = [Math.sin(angle), -Math.cos(angle)];
      const gradientLength = 26 * (Math.abs(Math.sin(angle)) + Math.abs(Math.cos(angle)));
      let checked = 0;
      tower.forEach((point, index) => {
        const s = along[index]!;
        if (s < region.from + 0.01 || s > region.to - 0.01 || offScreen(point)) return;
        checked++;
        // видима точка кривої лежить у своїй ділянці, а не в чужій
        expect(coverage(point, region.layers), `${region.name} ${point.join(',')}`).toBe(1);
        for (const other of regions) {
          if (other !== region) expect(coverage(point, other.layers), `${region.name} у ${other.name}`).toBe(0);
        }
        // точка градієнта p → позиція на шляху s = (p − a) / k збігається з довжиною дуги
        const p = (point[0] - 13) * dir[0] + (point[1] - 13) * dir[1] + gradientLength / 2;
        expect(Math.abs((p - region.a) / region.k - s), `${region.name} s=${s.toFixed(2)}`).toBeLessThan(0.15);
      });
      expect(checked, region.name).toBeGreaterThan(500);
    }
  });

  it('на межах ділянок шпиля немає шва: часткові покриття крайнього пікселя складаються рівно в 1', () => {
    // копії однієї частини світла (хвіст, голова) — в ізольованій групі й складаються (plus-lighter),
    // а не накладаються: при накладанні c і 1 − c дали б c + (1 − c)(1 − c·α) < 1, тобто лінію на вершині
    const groups = [...loginHtml.matchAll(/<g class="auth-trace-regions">([\s\S]*?)<\/g>/g)].map((match) =>
      [...(match[1] ?? '').matchAll(/<use class="([^"]+)"/g)].map((use) => use[1]),
    );
    expect(groups).toEqual([
      ['auth-trace-tail auth-trace-left', 'auth-trace-tail auth-trace-right', 'auth-trace-tail auth-trace-base'],
      ['auth-trace-head auth-trace-left', 'auth-trace-head auth-trace-right', 'auth-trace-head auth-trace-base'],
    ]);
    expect(prop(rule('.auth-trace-regions'), 'isolation')).toBe('isolate');
    expect(prop(rule('.auth-trace-regions use'), 'mix-blend-mode')).toBe('plus-lighter');

    // межі задають ті самі прямокутники: x = 13 ділить знак на половини L і R, B — основа
    const layers = (['left', 'right', 'base'] as const).map((name) => towerLayers(name));
    const keys = new Set(layers.flat().map(({ rect }) => rect.join(',')));
    expect(keys.has('0,0,13,26') && keys.has('13,0,13,26'), 'половини знака по бісектрисі вершини').toBe(true);
    // будь-які часткові покриття крайніх пікселів: L і R ділять піксель на межі (c і 1 − c),
    // B перетинає що завгодно; сума трьох ділянок — рівно 1
    for (const cL of [0, 0.13, 0.5, 0.79, 1]) {
      for (const cB of [0, 0.27, 0.5, 0.93, 1]) {
        const cover = (rect: Rect) => {
          const key = rect.join(',');
          if (key === '0,0,13,26') return cL;
          if (key === '13,0,13,26') return 1 - cL;
          if (key === '0,0,26,26') return 1;
          return cB;
        };
        const sum = layers.reduce((total, layer) => total + composeMask(layer, cover), 0);
        expect(sum, `cL=${cL} cB=${cB}`).toBeCloseTo(1, 9);
      }
    }
    // Chromium розмиває на 2 пікселі край прямокутника, який сам віднімає нижчі шари (наприклад «L без B»),
    // і такий край уже не доповнює сусідній. Тому віднімає лише шар на весь знак.
    for (const layer of layers) {
      layer.forEach(({ rect, op }) => {
        if (op === 'subtract') expect(rect).toEqual([0, 0, 26, 26]);
      });
    }
  });

  it('без масок CSS світла немає (інакше хвіст був би рівним непрозорим штрихом)', () => {
    expect(css).toMatch(
      /@supports not \(\(mask-composite: intersect\) and \(mix-blend-mode: plus-lighter\)\) \{\s*\.auth-trace \{\s*display: none;/,
    );
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
