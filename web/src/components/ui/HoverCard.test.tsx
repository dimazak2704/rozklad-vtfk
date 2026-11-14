import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { HoverDetails } from './HoverCard';
import { HOVER_WARM_MS, resetHoverCards } from './hover-intent';
import { HOVER_CARD_DELAY_MS, HOVER_DELAY_MS } from './Tooltip.helpers';

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
