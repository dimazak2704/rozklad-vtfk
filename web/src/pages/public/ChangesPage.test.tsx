import { onlineManager, QueryClient } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetSavedCopies } from '@/api/saved-copy';
import { SAVED_COPY_HEADER } from '@/api/saved-copy-plugin';
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

    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-06-04' });

    // номер і походження розпорядження — у картці «Джерело», а не в тексті сторінки
    await user.click(await screen.findByRole('button', { name: 'Джерело' }));
    expect(await screen.findByText(/паперове, внесене в систему/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /PDF/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /PNG/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/опубліковано/)).not.toBeInTheDocument();
  });
});

describe('«Заміни»: розпорядження з доповненнями', () => {
  it('називає доповнення, дає їхні PDF у «⋯» і позначає рядки з доповнень; заголовок — «Заміни»', async () => {
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

    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-12-22' });

    // заголовок — одне слово (не ламається на телефоні), дата — для читача екрана
    expect(await screen.findByRole('heading', { level: 1, name: /Заміни на вівторок, 22/ })).toBeInTheDocument();
    // рядок основного розпорядження без позначки, рядок доповнення — з нею
    expect(await screen.findByText(/з доповнення №49/)).toBeInTheDocument();
    // підзаголовок — лише дата й парність, підсумок — окремим рядком над списком
    expect(screen.getByRole('heading', { level: 2, name: /^2\sзаміни у 1\sгрупі$/ })).toBeInTheDocument();
    expect(screen.queryByText(/№43/)).not.toBeInTheDocument();
    // номери документів — у картці «Джерело» під списком
    await user.click(screen.getByRole('button', { name: 'Джерело' }));
    expect(await screen.findByText((_, element) => element?.tagName === 'SPAN' && element.textContent === 'доповнення №45, №49')).toBeInTheDocument();
    expect(screen.getByText(/опубліковано/)).toHaveTextContent('Розпорядження №43, опубліковано');
    await user.keyboard('{Escape}');
    expect(screen.queryByText(/з доповнення №43/)).not.toBeInTheDocument();
    // «Пари не буде» — один раз на рядок, а не значок і ще рядок
    expect(screen.getAllByText('Пари не буде')).toHaveLength(2);

    // файли документів — у меню «⋯», а не чотирма кнопками над списком
    expect(screen.queryByRole('link', { name: /Доповнення №45/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    expect(await screen.findByRole('menuitem', { name: /Доповнення №45 — PDF/ })).toHaveAttribute('href', expect.stringContaining('order-45.pdf'));
    expect(screen.getByRole('menuitem', { name: /Доповнення №49 — PDF/ })).toHaveAttribute('href', expect.stringContaining('order-49.pdf'));
    expect(screen.getByRole('menuitem', { name: /Розпорядження №43 — PDF/ })).toBeInTheDocument();
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

describe('«Заміни»: збережена копія Service Worker', () => {
  // четвер, 10:00 за Києвом: сьогодні навчальний день, і список сьогоднішній
  const TODAY = '2026-11-12';
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-12T08:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
    resetSavedCopies();
  });

  const todayChanges = {
    date: TODAY,
    parity: 'NUMERATOR',
    studyDay: true,
    weekdayLabel: 'Четвер',
    order: { id: 'order-50', number: 50, publishedAt: '2026-11-11T15:00:00Z', imported: false },
    amendments: [],
    groups: [
      {
        group: { id: 'g-4ki1', code: '4КІ1', course: 4 },
        items: [
          {
            pairLabel: '2',
            rowType: 'CANCELLATION',
            from: { discipline: 'Прогр.', teacher: 'Непийвода' },
            reasonText: 'Відп.',
            to: { discipline: null, teacher: null },
            room: null,
            subgroupNote: null,
          },
        ],
      },
    ],
  };

  it('сервер не відповів, а мережа є: не «наживо», а примітка з датою копії; свіжа відповідь її прибирає', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    let fromCache = true;
    server.use(
      http.get(apiPath('/public/changes'), () =>
        HttpResponse.json(todayChanges, {
          headers: fromCache ? { [SAVED_COPY_HEADER]: new Date('2026-11-05T06:15:00Z').toUTCString() } : {},
        }),
      ),
    );
    renderWithProviders(<ChangesPage />, { route: '/changes' });

    const note = (await screen.findByText(/Сервер не відповів/)).closest('[role="status"]');
    expect(note).toHaveTextContent('Сервер не відповів. Показано заміни, збережені 5 листопада о 08:15 — могли змінитися.');
    expect(screen.getByText('4КІ1')).toBeInTheDocument();
    expect(screen.queryByText('Оновлюється наживо')).not.toBeInTheDocument();

    fromCache = false;
    await userEvent.click(screen.getByRole('button', { name: 'Оновити' }));
    await vi.waitFor(() => expect(screen.queryByText(/Сервер не відповів/)).not.toBeInTheDocument());
    expect(screen.getByText('Оновлюється наживо')).toBeInTheDocument();
  });

  it('звичайна відповідь сервера на сьогодні — «наживо» і без примітки', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    server.use(http.get(apiPath('/public/changes'), () => HttpResponse.json(todayChanges)));
    renderWithProviders(<ChangesPage />, { route: '/changes' });

    expect(await screen.findByText('Оновлюється наживо')).toBeInTheDocument();
    expect(screen.queryByText(/збережен/)).not.toBeInTheDocument();
  });

  it('без інтернету список, що вже є, позначено як збережений, без кнопки «Оновити»', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    server.use(http.get(apiPath('/public/changes'), () => HttpResponse.json(todayChanges)));
    renderWithProviders(<ChangesPage />, { route: '/changes' });

    const note = (await screen.findByText(/Немає інтернету/)).closest('[role="status"]');
    expect(note).toHaveTextContent('Немає інтернету. Показано збережені заміни — могли змінитися.');
    expect(screen.queryByRole('button', { name: 'Оновити' })).not.toBeInTheDocument();
    expect(screen.queryByText('Оновлюється наживо')).not.toBeInTheDocument();
  });
});
