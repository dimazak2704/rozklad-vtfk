import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { onUnauthorized } from '@/api/client';
import { qk } from '@/api/queryKeys';
import type { CurrentUserState } from '@/auth/useCurrentUser';
import { groupNamed, roomNamed, teacherNamed } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import GroupSchedulePage from './GroupSchedulePage';
import RoomSchedulePage from './RoomSchedulePage';
import TeacherSchedulePage from './TeacherSchedulePage';

/**
 * Гість або викладач (персонал); токен шле справжній API-клієнт — його дає getAccessToken. `token: null` —
 * персонал, чий токен прострочений і не оновився.
 */
const auth = vi.hoisted((): { staff: boolean; token: string | null } => ({ staff: false, token: 'teacher-token' }));

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: (): Partial<CurrentUserState> =>
    auth.staff
      ? { status: 'authenticated', isAdmin: false, isTeacher: true, roles: ['TEACHER'] }
      : { status: 'anonymous', isAdmin: false, isTeacher: false, roles: [] },
}));
vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve(auth.staff ? auth.token : null) }));

/** Четвер, 12.11.2026: гостю відкрито з 5 листопада, тобто розклад — з тижня 2–6 листопада. */
const NOW = new Date('2026-11-12T08:00:00Z');

/** Новий елемент щоразу: з тим самим React не перемалював би сторінку після «виходу». */
const routes = () => (
  <Routes>
    <Route path="/g/:code" element={<GroupSchedulePage />} />
    <Route path="/t/:id" element={<TeacherSchedulePage />} />
    <Route path="/r/:id" element={<RoomSchedulePage />} />
  </Routes>
);

function renderSchedule(path: string) {
  const view = renderWithProviders(routes(), { route: path });
  // той самий клієнт кешу й та сама сторінка — змінився лише той, хто дивиться
  return { ...view, rerender: () => view.rerender(routes()) };
}

/** Чи надійшов запит з токеном: мок віддає давнє лише персоналу. */
function recordAuthorization(path: string): string[] {
  const seen: string[] = [];
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname.startsWith(apiPath(path))) seen.push(request.headers.get('Authorization') ?? 'none');
  });
  return seen;
}

const previousWeek = () => screen.getByRole('button', { name: /^Попередній тиждень/ });
/** Дні тижня розкладу на екрані, хоч би й заглушкою попереднього запиту: пояснення архіву їх не має. */
const weekShown = () => screen.queryAllByRole('heading', { name: /^Понеділок/ }).length > 0;

beforeEach(() => {
  auth.staff = false;
  auth.token = 'teacher-token';
  localStorage.clear();
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  server.events.removeAllListeners();
});

describe('Розклад: гість бачить лише останні тижні', () => {
  it('посилання на давній тиждень групи — пояснення замість помилки й шлях до найранішого тижня, де «‹» уже вимкнено', async () => {
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=week&date=2026-10-19');

    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
    expect(screen.getByText(/розклад з тижня 2–6\s+листопада/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/не завантажився/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Найраніший доступний тиждень' }));
    expect(await screen.findByRole('button', { name: /^2–6\s+листопада\. Обрати тиждень$/ })).toBeInTheDocument();
    expect(previousWeek()).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByText('Цей тиждень уже в архіві')).not.toBeInTheDocument();
  });

  it('на поточному тижні «‹» веде на тиждень межі, а далі — ні', async () => {
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=week');
    await screen.findByRole('button', { name: /, цей тиждень\. Обрати тиждень$/ });
    expect(previousWeek()).not.toHaveAttribute('aria-disabled');
    await user.click(previousWeek());
    expect(await screen.findByRole('button', { name: /^2–6\s+листопада\. Обрати тиждень$/ })).toBeInTheDocument();
    expect(previousWeek()).toHaveAttribute('aria-disabled', 'true');
    await user.click(previousWeek());
    expect(screen.getByRole('button', { name: /^2–6\s+листопада\. Обрати тиждень$/ })).toBeInTheDocument();
  });

  it('викладач: давній тиждень — не «такого викладача немає», а те саме пояснення', async () => {
    renderSchedule(`/t/${teacherNamed('Кренцін').id}?view=week&date=2026-10-19`);
    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
    expect(screen.queryByText('Такого викладача в розкладі немає')).not.toBeInTheDocument();
  });

  // імʼя викладача й назву аудиторії дає довідник: розклад давнього тижня гостю сервер не віддає
  it('викладач: давній тиждень, а довідник не завантажився — помилка з «Спробувати ще раз», а не вічний силует', async () => {
    server.use(http.get(apiPath('/public/teachers'), () => problem({ status: 503 })));
    const user = userEvent.setup();
    renderSchedule(`/t/${teacherNamed('Кренцін').id}?view=week&date=2026-10-19`);

    expect(await screen.findByText(/Розклад зараз не відкривається/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Розклад викладача' })).toBeInTheDocument();

    server.resetHandlers();
    await user.click(screen.getByRole('button', { name: 'Спробувати ще раз' }));
    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
  });

  it('аудиторія: давній тиждень, а довідник не завантажився — так само помилка, а не вічний силует', async () => {
    server.use(http.get(apiPath('/public/rooms'), () => problem({ status: 503 })));
    const user = userEvent.setup();
    renderSchedule(`/r/${roomNamed('6').id}?view=week&date=2026-10-19`);

    expect(await screen.findByText(/Розклад зараз не відкривається/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Розклад аудиторії' })).toBeInTheDocument();

    server.resetHandlers();
    await user.click(screen.getByRole('button', { name: 'Спробувати ще раз' }));
    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
  });
});

describe('Розклад: персонал бачить увесь архів', () => {
  it('давній тиждень відкривається з токеном, «‹» гортає без межі', async () => {
    auth.staff = true;
    const seen: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname.startsWith(apiPath('/public/schedule'))) seen.push(request.headers.get('Authorization') ?? 'none');
    });
    renderSchedule('/g/4КІ1?view=week&date=2026-10-19');

    expect(await screen.findByRole('button', { name: /^19–23\s+жовтня\. Обрати тиждень$/ })).toBeInTheDocument();
    expect(await screen.findAllByText('Понеділок')).not.toHaveLength(0);
    expect(screen.queryByText('Цей тиждень уже в архіві')).not.toBeInTheDocument();
    expect(previousWeek()).not.toHaveAttribute('aria-disabled');
    expect(seen.length).toBeGreaterThan(0);
    expect(new Set(seen)).toEqual(new Set(['Bearer teacher-token']));
  });

  it('вихід у тій самій вкладці: гість не бачить з памʼяті давнього тижня персоналу — ні даними, ні заглушкою', async () => {
    auth.staff = true;
    const view = renderSchedule('/g/4КІ1?view=week&date=2026-10-19');
    await waitFor(() => expect(weekShown()).toBe(true));

    // сесія входу скінчилася: той самий клієнт кешу, та сама сторінка, але вже гість
    auth.staff = false;
    view.rerender();
    // одразу, ще до відповіді сервера гостю: тиждень персоналу не стоїть і заглушкою попереднього запиту
    expect(weekShown()).toBe(false);
    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
    expect(weekShown()).toBe(false);
    const group = groupNamed('4КІ1').id;
    expect(view.queryClient.getQueryData(qk.public.schedule('GROUP', group, '2026-10-19', '2026-10-23', 'guest'))).toBeUndefined();

    // знову увійшов — не застряг на відповіді гостю
    auth.staff = true;
    view.rerender();
    await waitFor(() => expect(weekShown()).toBe(true));
    expect(screen.queryByText('Цей тиждень уже в архіві')).not.toBeInTheDocument();
  });

  it('токен прострочений і не оновився: запит не йде гостьовим, і під ключем персоналу немає відповіді гостю', async () => {
    auth.staff = true;
    auth.token = null;
    const seen = recordAuthorization('/public/schedule');
    const rejected: unknown[] = [];
    const off = onUnauthorized((error) => rejected.push(error));
    const view = renderSchedule('/g/4КІ1?view=week&date=2026-10-19');

    // 401 без запиту до сервера: обробник (UnauthorizedRedirect) забуде токен, і сторінка перепитає як гість
    await waitFor(() => expect(rejected).toHaveLength(1));
    expect(rejected[0]).toMatchObject({ status: 401 });
    expect(seen).toEqual([]);
    const group = groupNamed('4КІ1').id;
    expect(view.queryClient.getQueryData(qk.public.schedule('GROUP', group, '2026-10-19', '2026-10-23', 'staff'))).toBeUndefined();
    // гостьового пояснення «без входу» персоналу не показуємо
    expect(screen.queryByText('Цей тиждень уже в архіві')).not.toBeInTheDocument();

    // токен забуто — уже гість
    auth.staff = false;
    view.rerender();
    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
    expect(seen).toEqual(['none']);
    off();
  });

  it('файл .ics персонал завантажує з токеном — увесь семестр, а не лише останні тижні', async () => {
    auth.staff = true;
    const seen = recordAuthorization('/public/ics');
    const createObjectURL = vi.fn(() => 'blob:ics');
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=week');
    await waitFor(() => expect(weekShown()).toBe(true));

    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    await user.click(screen.getByRole('menuitem', { name: 'Додати в календар…' }));
    await user.click(await screen.findByRole('button', { name: /Файл \.ics/ }));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalledOnce());
    expect(seen).toEqual(['Bearer teacher-token']);
    Reflect.deleteProperty(URL, 'createObjectURL');
    Reflect.deleteProperty(URL, 'revokeObjectURL');
  });
});
