import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { User } from 'oidc-client-ts';
import { AuthProvider } from 'react-oidc-context';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { qk } from '@/api/queryKeys';
import { userManager } from '@/auth/userManager';
import { UnauthorizedRedirect } from '@/layouts/RootLayout';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import ChangesPage from './ChangesPage';

/**
 * Сесія персоналу закінчується просто на публічній сторінці: справжні AuthProvider, UserManager, API-клієнт і
 * UnauthorizedRedirect — як у застосунку. Замінено лише сервер (мок-API) і тихе оновлення токена.
 */

/** Четвер, 12.11.2026, 10:00 у Києві: гостю відкрито заміни з четверга, 5 листопада. */
const NOW = new Date('2026-11-12T08:00:00Z');

/** Access-токен викладача: інтерфейс читає з нього лише ролі, підпис перевіряє сервер. */
function teacherToken(): string {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode({ sub: 'teacher-1', roles: ['TEACHER'] })}.signature`;
}
const TOKEN = teacherToken();

/** Вхід викладача у вкладці: токен на годину в sessionStorage, звідки його бере UserManager. */
async function signInAsTeacher(): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  await userManager.storeUser(
    new User({
      access_token: TOKEN,
      token_type: 'Bearer',
      profile: { sub: 'teacher-1', iss: 'http://localhost/auth', aud: 'rozklad-web', iat: now, exp: now + 3600, preferred_username: 'krencin' },
      expires_at: now + 3600,
    }),
  );
}

function renderChanges(route: string) {
  return renderWithProviders(
    <AuthProvider userManager={userManager}>
      <UnauthorizedRedirect />
      <ChangesPage />
    </AuthProvider>,
    { route },
  );
}

/** Які запити замін пішли з токеном, а які — без нього. */
function recordAuthorization(): string[] {
  const seen: string[] = [];
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname.startsWith(apiPath('/public/changes'))) seen.push(request.headers.get('Authorization') ?? 'none');
  });
  return seen;
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  await signInAsTeacher();
});

afterEach(async () => {
  // знімає й таймери строку токена
  await userManager.removeUser();
  vi.useRealTimers();
  server.events.removeAllListeners();
});

describe('«Заміни»: сесія персоналу закінчилася на сторінці', () => {
  it('сервер не прийняв токен (401) — токен забуто без переходу на вхід, і сторінка перепитує як гість', async () => {
    const seen = recordAuthorization();
    // токен відкликали чи змінилися ключі: сервер відповідає 401 на будь-який запит з ним
    server.use(http.get(apiPath('/public/changes'), ({ request }) => (request.headers.has('Authorization') ? problem({ status: 401 }) : undefined)));
    renderChanges('/changes?date=2026-10-19');

    // гість бачить пояснення архіву — це вже відповідь сервера на запит без токена
    expect(await screen.findByText('Цей день уже в архіві')).toBeInTheDocument();
    expect(seen).toEqual([`Bearer ${TOKEN}`, 'none']);
    await expect(userManager.getUser()).resolves.toBeNull();
  });

  it('токен прострочився, а оновити не вдалося — не гостьовий запит під ключем персоналу, а гість після забутого токена', async () => {
    const seen = recordAuthorization();
    const user = userEvent.setup();
    const view = renderChanges('/changes');
    await screen.findByText(/Четвер, 12\s+листопада/);
    await waitFor(() => expect(seen).toEqual([`Bearer ${TOKEN}`]));

    // ноутбук спав дві години: сторінка досі «увійшла», а сервер входу недоступний
    vi.spyOn(userManager, 'signinSilent').mockRejectedValue(new TypeError('Failed to fetch'));
    vi.setSystemTime(new Date(NOW.getTime() + 2 * 60 * 60_000));
    await user.keyboard('{ArrowLeft}');

    expect(await screen.findByText(/Середа, 11\s+листопада/)).toBeInTheDocument();
    await waitFor(() => expect(seen).toEqual([`Bearer ${TOKEN}`, 'none']));
    // під ключем персоналу — лише відповіді персоналу; відповідь без токена лягла під ключ гостя
    expect(view.queryClient.getQueryData(qk.public.changes('2026-11-11', 'staff'))).toBeUndefined();
    await waitFor(() => expect(view.queryClient.getQueryData(qk.public.changes('2026-11-11', 'guest'))).toBeDefined());
    await expect(userManager.getUser()).resolves.toBeNull();
  });
});
