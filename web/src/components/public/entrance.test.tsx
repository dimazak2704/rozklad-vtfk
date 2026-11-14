import { render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ENTRANCE_MAX_STEPS, ENTRANCE_STEP_MS, entranceTargets, listTargets, tableColumnTargets, useEntrance } from './entrance';

function html(markup: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = markup;
  return root;
}

const stepsByText = (targets: { el: Element; step: number }[]) => targets.map(({ el, step }) => `${el.textContent}:${step}`);

describe('tableColumnTargets', () => {
  it('рахує справжній стовпець, коли комірка згори зайняла його на кілька рядків (практика на весь день)', () => {
    const root = html(`<table>
      <thead><tr><th>П</th><th>Пн</th><th>Вт</th><th>Ср</th></tr></thead>
      <tbody>
        <tr><th>1</th><td>a</td><td rowspan="2">практика</td><td>c</td></tr>
        <tr><th>2</th><td>d</td><td>f</td></tr>
        <tr><th>3</th><td>g</td><td>h</td><td>i</td></tr>
      </tbody></table>`);
    const table = root.querySelector('table')!;
    expect(stepsByText(tableColumnTargets(table))).toEqual([
      'П:0', 'Пн:1', 'Вт:2', 'Ср:3',
      '1:0', 'a:1', 'практика:2', 'c:3',
      // «f» стоїть третьою коміркою рядка, але це середа: вівторок ще зайнятий практикою
      '2:0', 'd:1', 'f:3',
      '3:0', 'g:1', 'h:2', 'i:3',
    ]);
  });

  it('стовпці правіше шостого дня не затримуються довше', () => {
    const cells = Array.from({ length: 9 }, (_, i) => `<td>${i}</td>`).join('');
    const table = html(`<table><tbody><tr>${cells}</tr></tbody></table>`).querySelector('table')!;
    expect(Math.max(...tableColumnTargets(table).map((t) => t.step))).toBe(ENTRANCE_MAX_STEPS);
  });
});

describe('listTargets', () => {
  it('бере лише зовнішні пункти й пропускає приховані службові рядки', () => {
    const root = html(`<ul>
      <li>A<ol><li>вкладений</li></ol></li>
      <li aria-hidden="true">лінія</li>
      <li>B</li>
    </ul><ol aria-hidden="true"><li>тінь</li></ol>`);
    expect(listTargets(root).map((t) => [t.el.firstChild?.textContent, t.step])).toEqual([
      ['A', 0],
      ['B', 1],
    ]);
  });

  it('без таблиці entranceTargets бере пункти списку', () => {
    expect(entranceTargets(html('<ul><li>x</li></ul>'))).toHaveLength(1);
  });
});

function Probe({ ready, markup }: { ready: boolean; markup: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEntrance(ref, ready);
  return <div ref={ref} dangerouslySetInnerHTML={{ __html: markup }} />;
}

describe('useEntrance', () => {
  const animate = vi.fn();
  let reduce = false;

  beforeEach(() => {
    reduce = false;
    animate.mockReset();
    Element.prototype.animate = animate;
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce'), media: query }) as MediaQueryList);
  });

  afterEach(() => {
    delete (Element.prototype as Partial<Element>).animate;
    vi.unstubAllGlobals();
  });

  it('проявляє пункти по черзі лише opacity і transform, коли вміст готовий, і лише один раз', () => {
    const markup = '<ul><li>1</li><li>2</li></ul>';
    const view = render(<Probe ready={false} markup={markup} />);
    expect(animate).not.toHaveBeenCalled();

    view.rerender(<Probe ready markup={markup} />);
    expect(animate).toHaveBeenCalledTimes(2);
    const [keyframes, options] = animate.mock.calls[1] as [Keyframe[], KeyframeAnimationOptions];
    expect(keyframes.flatMap((k) => Object.keys(k)).every((p) => p === 'opacity' || p === 'transform')).toBe(true);
    expect(options).toMatchObject({ delay: ENTRANCE_STEP_MS, fill: 'backwards' });

    // нові дані (інший тиждень) у тому самому монтуванні — без повтору
    view.rerender(<Probe ready markup="<ul><li>3</li></ul>" />);
    view.rerender(<Probe ready={false} markup={markup} />);
    view.rerender(<Probe ready markup={markup} />);
    expect(animate).toHaveBeenCalledTimes(2);
  });

  it('без вподобання руху нічого не рухається', () => {
    reduce = true;
    render(<Probe ready markup="<ul><li>1</li></ul>" />);
    expect(animate).not.toHaveBeenCalled();
  });
});
