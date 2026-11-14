import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { UnauthorizedRedirect } from './RootLayout';

const mocks = vi.hoisted(() => ({
  handler: null as ((error: unknown) => void) | null,
  removeUser: vi.fn(() => Promise.resolve()),
  dropAuthorizedCopies: vi.fn(() => Promise.resolve()),
}));

vi.mock('@/api/client', () => ({
  onUnauthorized: (handler: (error: unknown) => void) => {
    mocks.handler = handler;
    return () => undefined;
  },
}));
vi.mock('@/auth/userManager', () => ({ userManager: { removeUser: mocks.removeUser } }));
vi.mock('@/api/public-api-cache', () => ({ dropAuthorizedCopies: mocks.dropAuthorizedCopies }));

function Location() {
  const location = useLocation();
  return <p data-testid="location">{`${location.pathname}${location.search}`}</p>;
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <UnauthorizedRedirect />
      <Routes>
        <Route path="*" element={<Location />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('UnauthorizedRedirect', () => {
  beforeEach(() => {
    mocks.removeUser.mockClear();
    mocks.dropAuthorizedCopies.mockClear();
    window.sessionStorage.clear();
  });

  it('забуває відхилений токен один раз і веде на вхід зі сторінкою повернення', async () => {
    renderAt('/admin/changes?date=2026-10-19');
    const error = new ApiError({ status: 401, title: 'Потрібна автентифікація' });
    act(() => {
      mocks.handler?.(error);
      mocks.handler?.(error);
    });

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/login?returnTo=%2Fadmin%2Fchanges%3Fdate%3D2026-10-19'),
    );
    expect(mocks.removeUser).toHaveBeenCalledTimes(1);
    // відповіді з токеном, які зберегли попередні збірки PWA, теж забуваються
    expect(mocks.dropAuthorizedCopies).toHaveBeenCalledTimes(1);
  });

  it('запізнілі 401 сторінки після переходу на вхід не рахуються другою відмовою', async () => {
    renderAt('/admin/changes');
    const pageHandler = mocks.handler;
    const error = new ApiError({ status: 401, title: 'Потрібна автентифікація' });
    act(() => pageHandler?.(error));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login?returnTo=%2Fadmin%2Fchanges'));

    act(() => pageHandler?.(error));

    await waitFor(() => expect(mocks.removeUser).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('location')).not.toHaveTextContent('rejected=1');
  });

  it('друга відмова поспіль зупиняє автоматичний вхід (rejected=1)', async () => {
    window.sessionStorage.setItem('rozklad.auth.rejectedAt', String(Date.now()));
    renderAt('/admin');
    act(() => mocks.handler?.(new ApiError({ status: 401, title: 'Потрібна автентифікація' })));

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('rejected=1'));
  });

  it('на публічній сторінці відхилений токен забуває без переходу на вхід: сторінка перепитує як гість', async () => {
    // персонал питає розклад і заміни з токеном (архів); 401 тут — сервер токена не прийняв
    renderAt('/changes?date=2026-10-19');
    act(() => mocks.handler?.(new ApiError({ status: 401, title: 'Потрібна автентифікація' })));
    await waitFor(() => expect(mocks.removeUser).toHaveBeenCalledTimes(1));
    expect(mocks.dropAuthorizedCopies).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('location')).toHaveTextContent('/changes?date=2026-10-19');
    // це не повторна відмова при вході: автоматичний вхід на захищених сторінках не зупиняється
    expect(window.sessionStorage.getItem('rozklad.auth.rejectedAt')).toBeNull();
  });
});
