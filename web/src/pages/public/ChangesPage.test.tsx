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

describe('«Заміни»: імпортоване паперове розпорядження', () => {
  it('не пропонує PNG/PDF, яких для нього немає, і не видає час імпорту за час видання', async () => {
    server.use(
      http.get(apiPath('/public/changes'), () =>
        HttpResponse.json({
          date: '2026-06-04',
          parity: 'DENOMINATOR',
          weekdayLabel: 'Четвер',
          order: { id: 'order-105', number: 105, publishedAt: '2026-11-08T07:17:00Z', imported: true },
          groups: [
            {
              group: { id: null, code: '1КІ1', course: 1 },
              items: [
                {
                  pairLabel: '1',
                  rowType: 'REPLACEMENT',
                  from: { discipline: 'ФК', teacher: 'Бурбела' },
                  reasonText: 'Зв.зам.',
                  to: { discipline: 'ФВ', teacher: 'Остап’юк' },
                  room: 'С.з.',
                  subgroupNote: null,
                },
              ],
            },
          ],
        }),
      ),
    );

    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-06-04' });

    expect(await screen.findByText(/паперове, внесене в систему/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /PDF/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /PNG/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/опубліковано/)).not.toBeInTheDocument();
  });
});
