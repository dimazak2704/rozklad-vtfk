import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { LazyDayPicker } from './LazyDayPicker';

/** Перша спроба завантажити календар падає (немає мережі), друга вдається. */
const attempts = vi.hoisted(() => ({ count: 0 }));
vi.mock('./DayPickerUk', () => {
  attempts.count += 1;
  if (attempts.count === 1) throw new Error('Failed to fetch dynamically imported module');
  return { default: () => <div role="grid" aria-label="Календар" /> };
});

describe('LazyDayPicker без мережі', () => {
  it('пояснює, що календар не завантажився, і дає спробувати ще раз', async () => {
    const user = userEvent.setup();
    render(<LazyDayPicker mode="single" />);

    expect(await screen.findByText('Календар не завантажився. Перевірте зʼєднання.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Спробувати ще раз' }));

    expect(await screen.findByRole('grid', { name: 'Календар' })).toBeInTheDocument();
  });
});
