import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { ResolvedProgress } from './ResolvedProgress';

describe('«Вирішено N з M»', () => {
  it('пари, що чекають відповіді, — окремо, а не серед вирішених', () => {
    renderWithProviders(<ResolvedProgress done={9} waiting={1} total={13} />);
    expect(screen.getByText(/Вирішено/)).toHaveTextContent(/^Вирішено 9 з 13 пар · 1 чекає відповіді$/);
    expect(screen.getByRole('progressbar', { name: 'Вирішено пар' })).toHaveAttribute('aria-valuenow', '9');
  });

  it('«Усі пари вирішено» — лише коли нічого не чекаємо', () => {
    const { rerender } = renderWithProviders(<ResolvedProgress done={12} waiting={1} total={13} />);
    expect(screen.queryByText('Усі пари вирішено')).not.toBeInTheDocument();
    rerender(<ResolvedProgress done={13} total={13} />);
    expect(screen.getByText('Усі пари вирішено')).toBeInTheDocument();
  });
});
