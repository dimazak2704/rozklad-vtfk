import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/Tooltip';
import { resetMockStores } from '@/mocks/store';
import MyPreferencesPage from './MyPreferencesPage';

const WAIT = { timeout: 10_000 };

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  // сторінка тримає незбережене через useBlocker — потрібен роутер з даними
  const router = createMemoryRouter([{ path: '/me/preferences', element: <MyPreferencesPage /> }], { initialEntries: ['/me/preferences'] });
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  // середа 18.11: 4КІ1 на НС (16–27.11), тож у датованому розкладі цих двох тижнів її пар немає
  vi.setSystemTime(new Date('2026-11-18T08:00:00Z'));
  resetMockStores();
});
afterEach(() => vi.useRealTimers());

describe('сторінка «Побажання»', () => {
  it('мої пари — з базового розкладу семестру: групи на НС теж є', { timeout: 20_000 }, async () => {
    renderPage();
    // 4КІ1 · СТП у вівторок, 3 пара — постійний слот Кренціна, хоч група цього й наступного тижня на НС
    expect(await screen.findByRole('button', { name: /^Вівторок, 3 пара.*Ваші пари тут: 4КІ1 · СТП/ }, WAIT)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Понеділок, 2 пара.*4КІ1 · СТП/ })).toBeInTheDocument();
  });
});
