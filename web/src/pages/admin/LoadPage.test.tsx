import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import LoadPage from './LoadPage';

const WAIT = { timeout: 10_000 };

beforeEach(() => resetMockStores());

describe('Навантаження: пошук викладача', () => {
  it('рядки одного викладача — його підсумок, а підказки про групу немає', { timeout: 20_000 }, async () => {
    renderWithProviders(<LoadPage />, { route: '/admin/load?q=Кренцін' });
    const summary = await screen.findByLabelText(/^Навантаження: Кренцін/, undefined, WAIT);
    expect(summary).toHaveTextContent(/год на тиждень, за семестр [\d,]+ год — \d+\sрядк\S* у \d+\sгруп/);
    expect(screen.queryByText(/Оберіть групу, щоб побачити/)).not.toBeInTheDocument();
  });

  it('без пошуку й групи — підказка про групу, підсумку викладача немає', { timeout: 20_000 }, async () => {
    renderWithProviders(<LoadPage />, { route: '/admin/load' });
    expect(await screen.findByText(/Оберіть групу, щоб побачити/, undefined, WAIT)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByLabelText(/^Навантаження: /)).not.toBeInTheDocument());
  });
});
