import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type * as PostLogin from '@/auth/postLogin';
import AuthCallbackPage from './AuthCallbackPage';

const auth = vi.hoisted((): { state: unknown; status: string; error: Error | null } => ({
  state: undefined,
  status: 'authenticated',
  error: null,
}));

const stored = vi.hoisted((): { user: unknown; opened: string[] } => ({ user: null, opened: [] }));

vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { state: auth.state } }) }));
vi.mock('@/auth/userManager', () => ({ userManager: { getUser: () => Promise.resolve(stored.user) } }));
vi.mock('@/auth/postLogin', async (importOriginal) => ({
  ...(await importOriginal<typeof PostLogin>()),
  openAfterLogin: (path: string) => stored.opened.push(path),
}));
vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({ status: auth.status, roles: ['ADMIN'], error: auth.error, signIn: vi.fn() }),
}));

function fakeToken(roles: string[]): string {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode({ realm_access: { roles } })}.signature`;
}

function Location() {
  const location = useLocation();
  return <p data-testid="location">{`${location.pathname}${location.search}`}</p>;
}

function renderCallback(url = '/auth/callback?code=x&state=y') {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="*" element={<Location />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AuthCallbackPage', () => {
  it('після входу веде на сторінку, з якої почали вхід, а не на панель за роллю', () => {
    auth.state = { returnTo: '/admin/changes?date=2026-11-16' };
    renderCallback();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/changes?date=2026-11-16');
  });

  it('без сторінки повернення — за роллю', () => {
    auth.state = undefined;
    renderCallback();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('кнопка «Назад» після входу: застаріла адреса повернення не лякає помилкою, а відкриває сторінку за роллю', async () => {
    // AuthProvider не зміг обробити стару адресу вдруге: стан «невідомий», але сесія у вкладці є
    auth.status = 'anonymous';
    auth.error = new Error('No matching state found in storage');
    stored.user = { expired: false, access_token: fakeToken(['TEACHER']) };
    stored.opened.length = 0;
    renderCallback();
    await waitFor(() => expect(stored.opened).toEqual(['/me']));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    stored.user = null;
    auth.status = 'authenticated';
    auth.error = null;
  });

  it('помилка без входу — українською, без тексту бібліотеки', async () => {
    auth.status = 'anonymous';
    auth.error = new Error('No matching state found in storage');
    renderCallback();
    expect(await screen.findByRole('alert')).toHaveTextContent('Посилання входу застаріло');
    expect(screen.getByRole('alert')).not.toHaveTextContent('No matching state');
    auth.status = 'authenticated';
    auth.error = null;
  });

  it('вхід скасовано на сторінці Keycloak (error=access_denied): так і пише, хоч бібліотека скаржиться на state', async () => {
    auth.status = 'anonymous';
    // так react-oidc-context передає помилку: копія з innerError
    auth.error = Object.assign(new Error('No matching state found in storage'), { innerError: new Error('No matching state found in storage') });
    renderCallback('/auth/callback?error=access_denied&error_description=User%20denied&state=abc');
    expect(await screen.findByRole('alert')).toHaveTextContent('Вхід скасовано на сторінці входу коледжу.');
    expect(screen.getByRole('alert')).not.toHaveTextContent(/state|denied|застаріло/);
    auth.status = 'authenticated';
    auth.error = null;
  });
});
