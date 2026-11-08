import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import AuthCallbackPage from './AuthCallbackPage';

const auth = vi.hoisted((): { state: unknown; status: string; error: Error | null } => ({
  state: undefined,
  status: 'authenticated',
  error: null,
}));

vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { state: auth.state } }) }));
vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({ status: auth.status, roles: ['ADMIN'], error: auth.error, signIn: vi.fn() }),
}));

function Location() {
  const location = useLocation();
  return <p data-testid="location">{`${location.pathname}${location.search}`}</p>;
}

function renderCallback() {
  render(
    <MemoryRouter initialEntries={['/auth/callback?code=x&state=y']}>
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

  it('кнопка «Назад» після входу: застаріла адреса повернення не лякає помилкою, а веде на сторінку за роллю', () => {
    auth.state = undefined;
    auth.error = new Error('No matching state found in storage');
    renderCallback();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('помилка без входу — українською, без тексту бібліотеки', () => {
    auth.status = 'anonymous';
    auth.error = new Error('No matching state found in storage');
    renderCallback();
    expect(screen.getByRole('alert')).toHaveTextContent('Посилання входу застаріло');
    expect(screen.getByRole('alert')).not.toHaveTextContent('No matching state');
    auth.status = 'authenticated';
    auth.error = null;
  });
});
