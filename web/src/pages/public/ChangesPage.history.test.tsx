import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicMetaDto } from '@/api/types';
import type { CurrentUserState } from '@/auth/useCurrentUser';
import { publicMetaNow } from '@/mocks/handlers/meta';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import ChangesPage from './ChangesPage';

/** Хто «увійшов»: гість або викладач (персонал). Токен шле справжній API-клієнт — його дає getAccessToken. */
const auth = vi.hoisted(() => ({ staff: false }));

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: (): Partial<CurrentUserState> =>
    auth.staff
      ? { status: 'authenticated', isAdmin: false, isTeacher: true, roles: ['TEACHER'] }
      : { status: 'anonymous', isAdmin: false, isTeacher: false, roles: [] },
}));
vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve(auth.staff ? 'teacher-token' : null) }));

/** Четвер, 12.11.2026, 10:00 у Києві: гостю (типово 7 днів) відкрито заміни з четверга, 5 листопада. */
const NOW = new Date('2026-11-12T08:00:00Z');

/** Чи надійшов запит з токеном: мок замін і документів віддає давнє лише персоналу. */
function recordAuthorization(path: string): string[] {
  const seen: string[] = [];
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname.startsWith(apiPath(path))) seen.push(request.headers.get('Authorization') ?? 'none');
  });
  return seen;
}

/** Кнопка дня в календарі (react-day-picker позначає клітинку `data-day`). */
function dayButton(grid: HTMLElement, iso: string): HTMLElement {
  const cell = grid.querySelector<HTMLElement>(`[data-day="${iso}"]`);
  if (!cell) throw new Error(`Немає дня ${iso} у календарі`);
  return within(cell).getByRole('button');
}

beforeEach(() => {
  auth.staff = false;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  server.events.removeAllListeners();
  // jsdom не має createObjectURL: тест документів ставить свої — прибираємо їх
  Reflect.deleteProperty(URL, 'createObjectURL');
  Reflect.deleteProperty(URL, 'revokeObjectURL');
});

describe('«Заміни»: гість бачить лише останні дні', () => {
  it('давня дата за посиланням — спокійне пояснення, без помилки, і перехід до найранішої відкритої дати', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-10-19' });

    expect(await screen.findByText('Цей день уже в архіві')).toBeInTheDocument();
    expect(screen.getByText(/заміни з 5\s+листопада/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/Не вдалося/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Заміни на чт, 05.11' }));
    expect(await screen.findByText(/Четвер, 5\s+листопада/)).toBeInTheDocument();
    expect(await screen.findByText('Змін не було')).toBeInTheDocument();
  });

  it('у календарі межа — сама дата відкрита, день перед нею вимкнено, майбутнє відкрите; поруч підказка', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes' });
    await screen.findByText(/Четвер, 12\s+листопада/);
    await user.click(screen.getByRole('button', { name: 'Інша дата' }));

    const grid = await screen.findByRole('grid');
    expect(dayButton(grid, '2026-11-05')).toBeEnabled();
    expect(dayButton(grid, '2026-11-04')).toBeDisabled();
    expect(dayButton(grid, '2026-11-26')).toBeEnabled();
    expect(screen.getByText('Старіші заміни бачить лише персонал коледжу.')).toBeInTheDocument();

    await user.click(dayButton(grid, '2026-11-05'));
    expect(await screen.findByText(/Четвер, 5\s+листопада/)).toBeInTheDocument();
  });

  it('стрілка ← не веде за межу', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-11-06' });
    await screen.findByText(/Пʼятниця, 6\s+листопада/);
    await user.keyboard('{ArrowLeft}');
    expect(await screen.findByText(/Четвер, 5\s+листопада/)).toBeInTheDocument();
    await user.keyboard('{ArrowLeft}');
    // 4 листопада гостю закрите: дата лишається тією самою
    expect(screen.getByText(/Четвер, 5\s+листопада/)).toBeInTheDocument();
    expect(screen.queryByText(/Середа, 4\s+листопада/)).not.toBeInTheDocument();
  });

  it('файли розпорядження — звичайні посилання (без входу токена немає)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes' });
    await screen.findByText(/у \d+ груп/);
    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    expect(screen.getByRole('menuitem', { name: /— PDF$/ })).toHaveAttribute('href', expect.stringMatching(/^\/api\/documents\/orders\/.+\.pdf$/));
  });

  it('сервер старіший і межі не знає (meta без publicHistoryFrom) — гість гортає без обмежень', async () => {
    server.use(
      http.get(apiPath('/public/meta'), () => {
        const meta: Partial<PublicMetaDto> = publicMetaNow();
        delete meta.publicHistoryFrom;
        return HttpResponse.json(meta);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes' });
    await screen.findByText(/Четвер, 12\s+листопада/);
    await user.click(screen.getByRole('button', { name: 'Інша дата' }));
    expect(dayButton(await screen.findByRole('grid'), '2026-11-04')).toBeEnabled();
    expect(screen.queryByText('Старіші заміни бачить лише персонал коледжу.')).not.toBeInTheDocument();
  });
});

describe('«Заміни»: персонал бачить увесь архів', () => {
  it('давня дата відкривається з токеном, межі в календарі й підказки немає', async () => {
    auth.staff = true;
    const seen = recordAuthorization('/public/changes');
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-10-19' });

    // розпорядження №35: рядки 4КІ1 та інших груп
    expect(await screen.findByRole('link', { name: /^4КІ1/ })).toBeInTheDocument();
    expect(seen).toEqual(['Bearer teacher-token']);
    expect(screen.queryByText('Цей день уже в архіві')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Інша дата' }));
    // календар відкривається на жовтні: давні дні доступні
    expect(dayButton(await screen.findByRole('grid'), '2026-10-05')).toBeEnabled();
    expect(screen.queryByText('Старіші заміни бачить лише персонал коледжу.')).not.toBeInTheDocument();
  });

  it('файл давнього розпорядження відкривається запитом з токеном у новій вкладці', async () => {
    auth.staff = true;
    const seen = recordAuthorization('/documents/orders');
    const tab = { document: { title: '', body: { textContent: '' } }, location: { href: '' }, opener: {}, closed: false, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    const createObjectURL = vi.fn((blob: Blob) => (blob.size > 0 ? 'blob:order-35' : 'blob:empty'));
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-10-19' });
    await screen.findByRole('link', { name: /^4КІ1/ });

    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    const pdf = screen.getByRole('menuitem', { name: 'Розпорядження №35 — PDF' });
    // не посилання: звичайний перехід не несе токена, а давній файл сервер віддає лише персоналу
    expect(pdf).not.toHaveAttribute('href');
    await user.click(pdf);

    // вкладка відкривається в тому ж натисканні, адреса файлу — коли він завантажився
    expect(open).toHaveBeenCalledWith('', '_blank');
    await waitFor(() => expect(tab.location.href).toBe('blob:order-35'));
    expect(seen).toEqual(['Bearer teacher-token']);
    expect(tab.opener).toBeNull();
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
  });

  it('вихід у тій самій вкладці: гість не бачить з памʼяті давніх замін персоналу', async () => {
    auth.staff = true;
    const view = renderWithProviders(<ChangesPage />, { route: '/changes?date=2026-10-19' });
    expect(await screen.findByRole('link', { name: /^4КІ1/ })).toBeInTheDocument();

    // сесія входу скінчилася: той самий клієнт кешу, та сама сторінка, але вже гість
    auth.staff = false;
    view.rerender(<ChangesPage />);
    expect(await screen.findByText('Цей день уже в архіві')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^4КІ1/ })).not.toBeInTheDocument();

    // знову увійшов — не застряг на відповіді гостю
    auth.staff = true;
    view.rerender(<ChangesPage />);
    expect(await screen.findByRole('link', { name: /^4КІ1/ })).toBeInTheDocument();
  });
});
