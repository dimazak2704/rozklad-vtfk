import { onlineManager, QueryClient } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import ChangesPage from './ChangesPage';

describe('«Заміни» без мережі', () => {
  afterEach(() => onlineManager.setOnline(true));

  it('дата, якої немає на пристрої, — пояснення й повернення до сьогодні, а не вічне завантаження', async () => {
    // як у браузері: перша спроба падає без мережі, повтор TanStack ставить на паузу до появи мережі
    server.use(http.get(apiPath('/public/changes'), () => HttpResponse.error()));
    onlineManager.setOnline(false);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, retryDelay: 0, gcTime: 0 } } });

    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-11-10', queryClient });

    expect(await screen.findByText('Заміни на цю дату не збережено на пристрої')).toBeInTheDocument();
    expect(screen.queryByText('Завантажуємо заміни…')).not.toBeInTheDocument();
  });
});
