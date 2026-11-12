import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { HoverDetails } from './HoverCard';
import { HOVER_CARD_DELAY_MS, HOVER_DELAY_MS } from './Tooltip.helpers';

describe('HoverDetails', () => {
  beforeEach(() => {
    vi.useFakeTimers();
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
});
