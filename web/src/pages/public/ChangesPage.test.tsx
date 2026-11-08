import { onlineManager, QueryClient } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
          studyDay: true,
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

describe('«Заміни»: розпорядження з доповненнями', () => {
  it('називає доповнення, дає їхні PDF і позначає рядки з доповнень; заголовок — «на вівторок»', async () => {
    const row = (pairLabel: string, orderNumber: number) => ({
      pairLabel,
      rowType: 'CANCELLATION',
      from: { discipline: 'Прогр.', teacher: 'Непийвода' },
      reasonText: 'Відп.',
      to: { discipline: null, teacher: null },
      room: null,
      subgroupNote: null,
      orderNumber,
    });
    server.use(
      http.get(apiPath('/public/changes'), () =>
        HttpResponse.json({
          date: '2026-12-22',
          parity: 'NUMERATOR',
          studyDay: true,
          weekdayLabel: 'Вівторок',
          order: { id: 'order-43', number: 43, publishedAt: '2026-11-08T07:17:00Z', imported: false },
          amendments: [
            { id: 'order-45', number: 45, publishedAt: '2026-11-08T08:00:00Z', imported: false },
            { id: 'order-49', number: 49, publishedAt: '2026-11-08T09:00:00Z', imported: false },
          ],
          groups: [{ group: { id: 'g-4ki3', code: '4КІ3', course: 4 }, items: [row('2', 43), row('3', 49)] }],
        }),
      ),
    );

    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-12-22' });

    expect(await screen.findByRole('link', { name: /Доповнення №45/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: /Заміни на вівторок, 22/ })).toBeInTheDocument();
    expect(screen.getByText((_, element) => element?.tagName === 'SPAN' && element.textContent === 'доповнення №45, №49')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Доповнення №45/ })).toHaveAttribute('href', expect.stringContaining('order-45.pdf'));
    expect(screen.getByRole('link', { name: /Доповнення №49/ })).toHaveAttribute('href', expect.stringContaining('order-49.pdf'));
    // рядок основного розпорядження без позначки, рядок доповнення — з нею (таблиця й картки)
    expect(screen.getAllByText('Доповнення №49').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText('Доповнення №43')).not.toBeInTheDocument();
  });
});

describe('«Заміни» на дату без занять', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T08:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  const empty = (date: string, parity: string | null, studyDay: boolean) =>
    http.get(apiPath('/public/changes'), () =>
      HttpResponse.json({ date, parity, studyDay, weekdayLabel: 'Четвер', order: null, amendments: [], groups: [] }),
    );

  it('після семестру (канікули) не каже «діє звичайний розклад»', async () => {
    server.use(empty('2026-12-31', null, false));
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-12-31' });

    expect(await screen.findByText('Цього дня занять немає')).toBeInTheDocument();
    expect(screen.getByText(/Дата поза семестром — канікули/)).toBeInTheDocument();
    expect(screen.queryByText(/звичайний розклад/)).not.toBeInTheDocument();
    expect(screen.queryByText('Розпорядження ще не опубліковано')).not.toBeInTheDocument();
    expect(screen.queryByText('Розпорядження ще немає')).not.toBeInTheDocument();
  });

  it('свято в семестрі — теж без занять', async () => {
    server.use(empty('2026-12-24', 'NUMERATOR', false));
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-12-24' });

    expect(await screen.findByText('Цього дня занять немає')).toBeInTheDocument();
    expect(screen.getByText(/Свято або день без занять для всього коледжу/)).toBeInTheDocument();
  });

  it('навчальний день без розпорядження — як і раніше, «діє звичайний розклад»', async () => {
    server.use(empty('2026-12-24', 'NUMERATOR', true));
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-12-24' });

    expect(await screen.findByText('Розпорядження ще не опубліковано')).toBeInTheDocument();
    expect(screen.getByText(/Поки що діє звичайний розклад/)).toBeInTheDocument();
  });
});
