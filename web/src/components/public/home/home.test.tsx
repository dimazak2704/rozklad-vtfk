import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { noteResponseSource, resetSavedCopies } from '@/api/saved-copy';
import { SAVED_COPY_HEADER } from '@/api/saved-copy-plugin';
import { DEMO_WEEK } from '@/pages/dev/demo-data';
import { mockId } from '@/mocks/ids';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import HomePage from '@/pages/public/HomePage';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import { GroupsDirectory } from './GroupsDirectory';
import { StallNote } from './StallNote';
import { TodayChanges } from './TodayChanges';

beforeEach(() => {
  localStorage.clear();
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
});

describe('«Усі групи» на головній', () => {
  it('курси — рядками, курс розкривається на місці, і відкриті курси памʼятаються', async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<GroupsDirectory />);
    const fourth = await screen.findByRole('button', { name: /^4 курс · \d+\sгруп/ });
    // перший візит: жоден курс не відкритий, стіни з кодів немає
    expect(fourth).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('link', { name: '4КІ1' })).not.toBeInTheDocument();

    await user.click(fourth);
    expect(screen.getByRole('link', { name: '4КІ1' })).toHaveAttribute('href', expect.stringContaining('/g/'));
    expect(screen.queryByRole('link', { name: '1КІ2' })).not.toBeInTheDocument();
    unmount();

    // наступного разу 4 курс уже відкритий
    renderWithProviders(<GroupsDirectory />);
    expect(await screen.findByRole('link', { name: '4КІ1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^4 курс/ })).toHaveAttribute('aria-expanded', 'true');
  });

  it('поки людина нічого не відкривала, відкритий курс її групи', async () => {
    useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: { type: 'GROUP', id: mockId('group', '4КІ1'), label: '4КІ1', url: '/g/4КІ1' } });
    renderWithProviders(<GroupsDirectory />);
    const own = await screen.findByRole('link', { name: '4КІ1' });
    expect(own).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: /^1 курс/ })).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('«Заміни» на головній', () => {
  it('каже реченням, скільки замін, і веде до своєї групи', async () => {
    server.use(
      http.get(apiPath('/public/changes'), () =>
        HttpResponse.json({
          date: '2026-11-11',
          parity: 'NUMERATOR',
          studyDay: true,
          weekdayLabel: 'Середа',
          order: { id: 'order-52', number: 52, publishedAt: '2026-11-10T12:20:00Z', imported: false },
          amendments: [],
          groups: [
            {
              group: { id: 'g-4ki1', code: '4КІ1', course: 4 },
              items: [
                { pairLabel: '1', rowType: 'REPLACEMENT', from: { discipline: 'ДК', teacher: 'Паламарчук' }, reasonText: 'Відп.', to: { discipline: 'ДК', teacher: 'Резнік' }, room: '203', subgroupNote: null },
              ],
            },
            {
              group: { id: 'g-1ki2', code: '1КІ2', course: 1 },
              items: [
                { pairLabel: '3', rowType: 'CANCELLATION', from: { discipline: 'Матем.', teacher: 'Вихристюк' }, reasonText: 'Відп.', to: { discipline: null, teacher: null }, room: null, subgroupNote: null },
              ],
            },
          ],
        }),
      ),
    );
    useFavoritesStore.setState({ favorites: [], recent: [{ type: 'GROUP', id: 'g-4ki1', label: '4КІ1', url: '/g/4КІ1' }], lastViewed: null });
    const { container } = renderWithProviders(<TodayChanges />);
    expect(await screen.findByRole('link', { name: '4КІ1' })).toHaveAttribute('href', expect.stringContaining('view=day'));
    expect(container.textContent).toMatch(/2\sзаміни у 2\sгрупах, серед них у 4КІ1\./);
    // жодних табличок з кодами всіх груп
    expect(screen.queryByRole('link', { name: /1КІ2/ })).not.toBeInTheDocument();
  });
});

describe('Головна без сервера', () => {
  it('одна помилка на сторінку з однією кнопкою, а не чотири однакові', async () => {
    server.use(http.get(apiPath('/public/*'), () => problem({ status: 503, detail: 'Сервіс недоступний.' })));
    renderWithProviders(<HomePage />);
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Спробувати ще раз' })).toHaveLength(1));
    expect(screen.getByRole('alert')).toHaveTextContent('Розклад зараз не завантажується');
    // пошук лишається — ним можна скористатися, щойно сервер оживе
    expect(screen.getByRole('combobox', { name: /Знайти розклад/ })).toBeInTheDocument();
  });
});

describe('Рядок «немає звʼязку»', () => {
  it('запит на паузі без мережі — спокійне речення замість силуету', () => {
    renderWithProviders(<StallNote queries={[{ isPending: true, fetchStatus: 'paused', refetch: vi.fn() }]} />);
    expect(screen.getByRole('status')).toHaveTextContent('Немає звʼязку. Розклад завантажиться сам, щойно зʼявиться інтернет.');
  });

  it('коли все завантажилось — мовчить', () => {
    const { container } = renderWithProviders(<StallNote queries={[{ isPending: false, fetchStatus: 'idle', refetch: vi.fn() }]} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('Головна: збережена копія Service Worker, коли мережа є', () => {
  afterEach(() => resetSavedCopies());
  const SAVED = { [SAVED_COPY_HEADER]: new Date('2026-11-05T06:15:00Z').toUTCString() };
  const changesOf = (date: string) => ({ date, parity: 'NUMERATOR', studyDay: true, weekdayLabel: 'Четвер', order: null, amendments: [], groups: [] });

  it('заміни прийшли з кешу — рядок з датою копії замість мовчанки; свіжа відповідь його прибирає', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    let fromCache = true;
    server.use(
      http.get(apiPath('/public/changes'), ({ request }) =>
        HttpResponse.json(changesOf(new URL(request.url).searchParams.get('date') ?? ''), { headers: fromCache ? SAVED : {} }),
      ),
    );
    renderWithProviders(<HomePage />);

    const note = (await screen.findByText(/Сервер не відповів/)).closest('[role="status"]');
    expect(note).toHaveTextContent('Сервер не відповів. Показано розклад, збережений 5 листопада о 08:15 — заміни могли змінитися.');

    fromCache = false;
    await userEvent.click(screen.getByRole('button', { name: 'Оновити' }));
    await waitFor(() => expect(screen.queryByText(/Сервер не відповів/)).not.toBeInTheDocument());
  });

  it('день своєї групи з кешу — теж кажемо', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: { type: 'GROUP', id: mockId('group', '4КІ1'), label: '4КІ1', url: '/g/4КІ1' } });
    server.use(http.get(apiPath('/public/schedule/:type/:id'), () => HttpResponse.json(DEMO_WEEK, { headers: SAVED })));
    renderWithProviders(<HomePage />);

    expect(await screen.findByText(/Показано розклад, збережений 5 листопада о 08:15/)).toBeInTheDocument();
  });

  it('звичайні відповіді сервера — без рядка', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    let served = false;
    server.use(
      http.get(apiPath('/public/changes'), ({ request }) => {
        served = true;
        return HttpResponse.json(changesOf(new URL(request.url).searchParams.get('date') ?? ''));
      }),
    );
    renderWithProviders(<HomePage />);
    await waitFor(() => expect(served).toBe(true));
    await screen.findByRole('heading', { name: 'Знайти розклад' });
    expect(screen.queryByText(/Сервер не відповів/)).not.toBeInTheDocument();
  });

  it('копія сторінки без своєї адреси в savedUrls рядка не викликає', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    noteResponseSource('/api/public/changes?date=2026-11-12', new Headers(SAVED));
    const idle = { isPending: false, fetchStatus: 'idle' as const, refetch: vi.fn() };
    const { container, rerender } = renderWithProviders(<StallNote queries={[idle]} savedUrls={['/api/public/meta']} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<StallNote queries={[idle]} savedUrls={['/api/public/meta', '/api/public/changes?date=2026-11-12']} />);
    expect(screen.getByRole('status')).toHaveTextContent('Показано розклад, збережений 5 листопада о 08:15');
  });
});
