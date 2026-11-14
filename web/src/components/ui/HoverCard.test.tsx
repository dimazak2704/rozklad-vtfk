import { act, fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { HoverDetails } from './HoverCard';
import { HOVER_SWITCH_REST_MS, HOVER_WARM_MS, inTriangle, resetHoverCards, safeTriangle } from './hover-intent';
import { HOVER_CARD_CLOSE_DELAY_MS, HOVER_CARD_DELAY_MS, HOVER_DELAY_MS } from './Tooltip.helpers';

describe('HoverDetails', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    resetHoverCards();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const hover = (el: HTMLElement) => fireEvent.pointerOver(el, { pointerType: 'mouse' });
  const leave = (el: HTMLElement) => fireEvent.pointerOut(el, { pointerType: 'mouse' });

  it('картка відкривається пізніше за дрібну підказку: повільний огляд рядка її не відкриває', () => {
    expect(HOVER_CARD_DELAY_MS).toBeGreaterThanOrEqual(500);
    expect(HOVER_CARD_DELAY_MS).toBeGreaterThan(HOVER_DELAY_MS);
    renderWithProviders(
      <HoverDetails label="Деталі заняття" content={<p>Повна назва</p>}>
        <button type="button">Прогр.</button>
      </HoverDetails>,
    );
    const trigger = screen.getByRole('button', { name: 'Прогр.' });
    hover(trigger);
    // ≈ 400 мс на клітинку: курсор уже пішов далі, картки не було
    act(() => {
      vi.advanceTimersByTime(400);
    });
    expect(screen.queryByText('Повна назва')).not.toBeInTheDocument();
    leave(trigger);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    expect(screen.queryByText('Повна назва')).not.toBeInTheDocument();
  });

  it('клік по кнопці, чию картку вже відкрило наведення, лишає картку відкритою, а другий клік закриває', () => {
    renderWithProviders(
      <HoverDetails label="Джерело" content={<p>Розпорядження №43</p>}>
        <button type="button">Джерело</button>
      </HoverDetails>,
    );
    const trigger = screen.getByRole('button', { name: 'Джерело' });
    hover(trigger);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    expect(screen.getByText('Розпорядження №43')).toBeInTheDocument();

    fireEvent.click(trigger);
    expect(screen.getByText('Розпорядження №43')).toBeInTheDocument();
    fireEvent.click(trigger);
    act(() => {
      vi.runAllTimers();
    });
    expect(screen.queryByText('Розпорядження №43')).not.toBeInTheDocument();
  });

  it('курсор затримався на клітинці — картка відкривається', () => {
    renderWithProviders(
      <HoverDetails label="Деталі заняття" content={<p>Повна назва</p>}>
        <button type="button">Прогр.</button>
      </HoverDetails>,
    );
    hover(screen.getByRole('button', { name: 'Прогр.' }));
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    expect(screen.getByText('Повна назва')).toBeInTheDocument();
  });

  it('картка від наведення, закрившись, не ставить фокус на клітинку: кільця фокусу від миші немає', () => {
    renderWithProviders(
      <HoverDetails label="Деталі заняття" content={<p>Повна назва</p>}>
        <button type="button">Прогр.</button>
      </HoverDetails>,
    );
    const trigger = screen.getByRole('button', { name: 'Прогр.' });
    const before = document.activeElement;
    hover(trigger);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    expect(screen.getByText('Повна назва')).toBeInTheDocument();
    leave(trigger);
    // закриття, а потім — відкладене повернення фокуса Radix (setTimeout після розмонтування)
    act(() => {
      vi.runAllTimers();
    });
    act(() => {
      vi.runAllTimers();
    });
    expect(screen.queryByText('Повна назва')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(before);
    expect(trigger).not.toHaveFocus();
  });

  it('відкрита з клавіатури картка після Escape повертає фокус на тригер', () => {
    renderWithProviders(
      <HoverDetails label="Деталі заняття" content={<p>Повна назва</p>}>
        <button type="button">Прогр.</button>
      </HoverDetails>,
    );
    const trigger = screen.getByRole('button', { name: 'Прогр.' });
    act(() => {
      trigger.focus();
    });
    fireEvent.click(trigger);
    expect(screen.getByText('Повна назва')).toBeInTheDocument();
    act(() => {
      (document.activeElement as HTMLElement).blur();
    });
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
    // закриття, а потім — відкладене повернення фокуса Radix (setTimeout після розмонтування)
    act(() => {
      vi.runAllTimers();
    });
    act(() => {
      vi.runAllTimers();
    });
    expect(screen.queryByText('Повна назва')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('поки картка відкрита, сусідня клітинка показує свою одразу, а попередня зникає без анімації', () => {
    renderWithProviders(
      <>
        <HoverDetails label="Понеділок" content={<p>Математика</p>}>
          <button type="button">Матем.</button>
        </HoverDetails>
        <HoverDetails label="Вівторок" content={<p>Зарубіжна література</p>}>
          <button type="button">Зар.літ.</button>
        </HoverDetails>
      </>,
    );
    const monday = screen.getByRole('button', { name: 'Матем.' });
    const tuesday = screen.getByRole('button', { name: 'Зар.літ.' });
    hover(monday);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    expect(screen.getByText('Математика')).toBeInTheDocument();
    // курсор переходить на сусідню клітинку, поки перша картка ще відкрита (вона закрилась би через 100 мс)
    leave(monday);
    hover(tuesday);
    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(screen.getByText('Зарубіжна література')).toBeInTheDocument();
    expect(screen.queryByText('Математика')).not.toBeInTheDocument();
  });

  it('розігрів минає: після паузи без карток наступна знову чекає повну затримку', () => {
    renderWithProviders(
      <>
        <HoverDetails label="Понеділок" content={<p>Математика</p>}>
          <button type="button">Матем.</button>
        </HoverDetails>
        <HoverDetails label="Вівторок" content={<p>Зарубіжна література</p>}>
          <button type="button">Зар.літ.</button>
        </HoverDetails>
      </>,
    );
    const monday = screen.getByRole('button', { name: 'Матем.' });
    hover(monday);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    leave(monday);
    act(() => {
      vi.advanceTimersByTime(HOVER_WARM_MS + 200);
    });
    expect(screen.queryByText('Математика')).not.toBeInTheDocument();
    hover(screen.getByRole('button', { name: 'Зар.літ.' }));
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS - 50);
    });
    expect(screen.queryByText('Зарубіжна література')).not.toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(50);
    });
    expect(screen.getByText('Зарубіжна література')).toBeInTheDocument();
  });
});

/**
 * Безпечна зона: дві пари одна над одною (A зверху, B під нею), картка A відкривається під A й накриває B.
 * Дорогою від A до картки курсор проходить 6 px щілини над B — саме там сусідня пара перехоплювала картку.
 *
 *   A       0..200 × 0..60
 *   B       0..200 × 60..120
 *   картка  0..336 × 66..266
 */
describe('HoverDetails: безпечна зона до картки', () => {
  const CARD_RECT = { left: 0, top: 66, right: 336, bottom: 266 };

  beforeEach(() => {
    vi.useFakeTimers();
    resetHoverCards();
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const r = this.getAttribute('role') === 'dialog' ? CARD_RECT : { left: 0, top: 0, right: 0, bottom: 0 };
      return { ...r, x: r.left, y: r.top, width: r.right - r.left, height: r.bottom - r.top, toJSON: () => r };
    });
  });
  afterEach(() => {
    resetHoverCards();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  const at = (x: number, y: number) => ({ pointerType: 'mouse', clientX: x, clientY: y });
  const over = (el: Element, x: number, y: number) => fireEvent.pointerOver(el, at(x, y));
  const out = (el: Element, x: number, y: number) => fireEvent.pointerOut(el, at(x, y));
  const move = (x: number, y: number) => fireEvent.pointerMove(window, at(x, y));

  function renderPair() {
    renderWithProviders(
      <>
        <HoverDetails label="1 пара" content={<p>Математика</p>}>
          <button type="button">Матем.</button>
        </HoverDetails>
        <HoverDetails label="2 пара" content={<p>Фізика</p>}>
          <button type="button">Фізика (клітинка)</button>
        </HoverDetails>
      </>,
    );
    const a = screen.getByRole('button', { name: 'Матем.' });
    const b = screen.getByRole('button', { name: 'Фізика (клітинка)' });
    over(a, 100, 30);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    expect(screen.getByText('Математика')).toBeInTheDocument();
    return { a, b };
  }

  it('курсор іде до картки через сусідню пару — картка лишається, сусідня її не перехоплює', () => {
    const { a, b } = renderPair();
    // виходимо з A донизу, перетинаємо верх B і заходимо на картку
    out(a, 120, 60);
    over(b, 122, 62);
    move(124, 64);
    act(() => {
      vi.advanceTimersByTime(HOVER_SWITCH_REST_MS - 50);
    });
    out(b, 126, 66);
    over(screen.getByRole('dialog'), 126, 70);
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByText('Математика')).toBeInTheDocument();
    expect(screen.queryByText('Фізика')).not.toBeInTheDocument();
  });

  it('повільний рух трикутником теж не перемикає: кожен рух відкладає перехоплення', () => {
    const { a, b } = renderPair();
    out(a, 120, 60);
    over(b, 121, 61);
    for (const y of [62, 63, 64, 65]) {
      act(() => {
        vi.advanceTimersByTime(HOVER_SWITCH_REST_MS - 30);
      });
      move(120 + (y - 60), y);
    }
    expect(screen.getByText('Математика')).toBeInTheDocument();
    expect(screen.queryByText('Фізика')).not.toBeInTheDocument();
  });

  it('курсор зупинився на сусідній парі в трикутнику — вона забирає картку за коротку паузу', () => {
    const { a, b } = renderPair();
    out(a, 120, 60);
    over(b, 122, 62);
    act(() => {
      vi.advanceTimersByTime(HOVER_SWITCH_REST_MS - 1);
    });
    expect(screen.getByText('Математика')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByText('Фізика')).toBeInTheDocument();
    expect(screen.queryByText('Математика')).not.toBeInTheDocument();
  });

  it('курсор вийшов із трикутника на сусідній парі — перемикання одразу, без паузи', () => {
    const { a, b } = renderPair();
    out(a, 120, 60);
    over(b, 122, 62);
    // ліворуч, далеко від напрямку на картку
    move(5, 64);
    expect(screen.getByText('Фізика')).toBeInTheDocument();
    expect(screen.queryByText('Математика')).not.toBeInTheDocument();
  });

  it('курсор, що пішов з тригера не в бік картки, закриває її з звичайною затримкою', () => {
    const { a } = renderPair();
    // праворуч з A — геть від картки, що під ним
    out(a, 200, 30);
    move(230, 28);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_CLOSE_DELAY_MS - 1);
    });
    expect(screen.getByText('Математика')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText('Математика')).not.toBeInTheDocument();
  });

  it('курсор на картці тримає її відкритою, а з картки на іншу пару — перемикання одразу', () => {
    const { a, b } = renderPair();
    out(a, 120, 60);
    const card = screen.getByRole('dialog');
    over(card, 120, 80);
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(screen.getByText('Математика')).toBeInTheDocument();
    out(card, 340, 100);
    over(b, 150, 100);
    expect(screen.getByText('Фізика')).toBeInTheDocument();
  });

  it('клік по посиланню в картці закриває її одразу', () => {
    renderWithProviders(
      <HoverDetails label="1 пара" content={<a href="/t/1">Непийвода</a>}>
        <button type="button">Матем.</button>
      </HoverDetails>,
    );
    over(screen.getByRole('button', { name: 'Матем.' }), 100, 30);
    act(() => {
      vi.advanceTimersByTime(HOVER_CARD_DELAY_MS);
    });
    const link = screen.getByRole('link', { name: 'Непийвода' });
    link.addEventListener('click', (event) => event.preventDefault());
    fireEvent.click(link);
    act(() => {
      vi.runAllTimers();
    });
    expect(screen.queryByRole('link', { name: 'Непийвода' })).not.toBeInTheDocument();
  });
});

describe('safeTriangle', () => {
  const rect = { left: 0, top: 66, right: 336, bottom: 266 };

  it('вершина — точка виходу (трохи позаду), основа — ближча сторона картки', () => {
    const { apex, near } = safeTriangle({ x: 120, y: 60 }, rect);
    expect(apex).toEqual({ x: 120, y: 58 });
    expect(near).toEqual([
      { x: 0, y: 66 },
      { x: 336, y: 66 },
    ]);
    expect(inTriangle({ x: 122, y: 62 }, apex, ...near)).toBe(true);
    expect(inTriangle({ x: 5, y: 62 }, apex, ...near)).toBe(false);
  });

  it('картка ліворуч чи праворуч від точки — основа на бічній стороні', () => {
    expect(safeTriangle({ x: 400, y: 100 }, rect).near).toEqual([
      { x: 336, y: 66 },
      { x: 336, y: 266 },
    ]);
    expect(safeTriangle({ x: 300, y: 300 }, rect).near[0]).toEqual({ x: 0, y: 266 });
  });
});

describe('HoverDetails: клавіатура', () => {
  beforeEach(() => resetHoverCards());

  it('картка, відкрита Enter, бере фокус, і наступний Tab веде до посилання в ній, а не до сусідньої клітинки', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <HoverDetails label="1 пара" content={<a href="/t/1">Непийвода</a>}>
          <button type="button">Матем.</button>
        </HoverDetails>
        <button type="button">Сусідня клітинка</button>
      </>,
    );
    await user.tab();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: '1 пара' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Непийвода' })).toHaveFocus();
  });
});
