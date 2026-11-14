import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { notify } from '@/components/ui';
import { renderWithProviders } from '@/test/render';
import { WeekNav } from './WeekNav';

/** Клітинка дня в календарі (react-day-picker позначає її `data-day`). */
function dayCell(grid: HTMLElement, iso: string): HTMLElement {
  const cell = grid.querySelector<HTMLElement>(`[data-day="${iso}"]`);
  if (!cell) throw new Error(`Немає дня ${iso} у календарі`);
  return cell;
}

// гостю відкрито з четверга, 5 листопада: розклад — цілими тижнями, тобто з понеділка, 2 листопада
const MIN_DATE = '2026-11-05';
const TODAY = '2026-11-12';

describe('WeekNav: межа архіву для гостя', () => {
  it('на тижні межі «‹» вимкнено з поясненням і не гортає далі; тиждень пізніше — гортає', async () => {
    const info = vi.spyOn(notify, 'info').mockImplementation(() => 0);
    const onChange = vi.fn();
    const user = userEvent.setup();
    const view = renderWithProviders(<WeekNav value="2026-11-04" onChange={onChange} today={TODAY} minDate={MIN_DATE} hotkeys />);

    const previous = screen.getByRole('button', { name: /^Попередній тиждень/ });
    expect(previous).toHaveAttribute('aria-disabled', 'true');
    expect(previous).toHaveAccessibleName('Попередній тиждень — лише для персоналу коледжу');
    await user.click(previous);
    await user.keyboard('{ArrowLeft}');
    expect(onChange).not.toHaveBeenCalled();
    expect(info).toHaveBeenCalledWith('Старіший розклад бачить лише персонал коледжу.');

    view.rerender(<WeekNav value="2026-11-11" onChange={onChange} today={TODAY} minDate={MIN_DATE} hotkeys />);
    const enabled = screen.getByRole('button', { name: 'Попередній тиждень' });
    expect(enabled).not.toHaveAttribute('aria-disabled');
    await user.click(enabled);
    expect(onChange).toHaveBeenCalledWith('2026-11-04');
  });

  it('календар не дає обрати тиждень, давніший за межу, і каже чому', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<WeekNav value="2026-11-11" onChange={onChange} today={TODAY} minDate={MIN_DATE} />);
    await user.click(screen.getByRole('button', { name: /^Обрати тиждень/ }));
    const grid = await screen.findByRole('grid');
    // понеділок тижня межі відкритий (тиждень показується цілим), дні перед ним — ні
    expect(within(dayCell(grid, '2026-11-02')).getByRole('button')).toBeEnabled();
    expect(within(dayCell(grid, '2026-11-04')).getByRole('button')).toBeEnabled();
    expect(dayCell(grid, '2026-11-01')).toHaveAttribute('data-disabled', 'true');
    expect(dayCell(grid, '2026-10-30')).toHaveAttribute('data-disabled', 'true');
    expect(screen.getByText('Старіший розклад бачить лише персонал коледжу.')).toBeInTheDocument();
    await user.click(within(dayCell(grid, '2026-11-03')).getByRole('button'));
    expect(onChange).toHaveBeenCalledWith('2026-11-03');
  });

  it('без межі (персонал, розклад кабінету) «‹» завжди гортає', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<WeekNav value="2026-11-04" onChange={onChange} today={TODAY} />);
    const previous = screen.getByRole('button', { name: 'Попередній тиждень' });
    expect(previous).not.toHaveAttribute('aria-disabled');
    await user.click(previous);
    expect(onChange).toHaveBeenCalledWith('2026-10-28');
    await user.click(screen.getByRole('button', { name: /^Обрати тиждень/ }));
    expect(screen.queryByText('Старіший розклад бачить лише персонал коледжу.')).not.toBeInTheDocument();
  });
});
