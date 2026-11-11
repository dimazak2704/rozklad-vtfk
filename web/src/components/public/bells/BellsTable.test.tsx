import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { BellProfileDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { BellsTable } from './BellsTable';

const slot = (weekday: 1 | 2 | 3 | 4 | 5, pair: number, startsAt: string, endsAt: string) => ({ weekday, pair, startsAt, endsAt });

/** Пн–Чт однакові: перерви 10 і 20 хв; пʼятниця коротша. */
const PROFILE = {
  id: 'p',
  name: '2 зміни, 60 хв',
  slots: [1, 2, 3, 4].flatMap((weekday) => [
    slot(weekday as 1, 1, '08:30', '09:30'),
    slot(weekday as 1, 2, '09:40', '10:40'),
    slot(weekday as 1, 3, '11:00', '12:00'),
  ]),
} as unknown as BellProfileDto;
PROFILE.slots.push(slot(5, 1, '08:30', '09:30'));

describe('BellsTable', () => {
  it('пише лише довгу перерву, коротку — ні', () => {
    renderWithProviders(<BellsTable profile={PROFILE} todayWeekday={null} />);
    // текст нормалізується: нерозривний пробіл стає звичайним
    expect(screen.getByText('перерва 20 хв')).toBeInTheDocument();
    expect(screen.queryByText(/перерва 10/)).not.toBeInTheDocument();
  });

  it('«Пн–Чт» у заголовку — одне ціле, «сьогодні» окремо', () => {
    renderWithProviders(<BellsTable profile={PROFILE} todayWeekday={3} />);
    const header = screen.getByRole('columnheader', { current: 'date' });
    expect(within(header).getByText('Пн–Чт')).toHaveClass('whitespace-nowrap');
    expect(within(header).getByText('сьогодні')).toBeInTheDocument();
  });
});
