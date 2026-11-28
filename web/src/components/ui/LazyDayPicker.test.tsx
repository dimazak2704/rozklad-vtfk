import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { preloadDayPicker } from './day-picker-loader';
import { LazyDayPicker } from './LazyDayPicker';

/** Перше перетворення react-day-picker у vitest повільне: чекаємо календар довше за типову секунду. */
const LOAD = { timeout: 10_000 };

describe('LazyDayPicker', () => {
  it('поки код календаря вантажиться, на його місці кільце; потім календар українською', async () => {
    render(<LazyDayPicker mode="single" defaultMonth={new Date(2026, 10, 1)} />);

    expect(screen.getByRole('status')).toHaveTextContent('Завантажуємо календар');
    expect(await screen.findByRole('grid', undefined, LOAD)).toBeInTheDocument();
    expect(screen.getByText(/листопад 2026/i)).toBeInTheDocument();
  });

  it('після першого завантаження календар малюється одразу, без кільця', async () => {
    preloadDayPicker();
    render(<LazyDayPicker mode="single" defaultMonth={new Date(2026, 10, 1)} />);
    await screen.findByRole('grid', undefined, LOAD);

    render(<LazyDayPicker mode="single" defaultMonth={new Date(2026, 11, 1)} />);

    expect(screen.getAllByRole('grid')).toHaveLength(2);
    expect(screen.queryByText('Завантажуємо календар')).toBeNull();
  });
});
