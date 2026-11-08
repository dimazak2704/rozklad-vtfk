import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { UnauthorizedRedirect } from './RootLayout';

const mocks = vi.hoisted(() => ({
  handler: null as ((error: unknown) => void) | null,
  removeUser: vi.fn(() => Promise.resolve()),
}));

vi.mock('@/api/client', () => ({
  onUnauthorized: (handler: (error: unknown) => void) => {
    mocks.handler = handler;
    return () => undefined;
  },
}));
vi.mock('@/auth/userManager', () => ({ userManager: { removeUser: mocks.removeUser } }));

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
  });

  it('друга відмова поспіль зупиняє автоматичний вхід (rejected=1)', async () => {
    window.sessionStorage.setItem('rozklad.auth.rejectedAt', String(Date.now()));
    renderAt('/admin');
    act(() => mocks.handler?.(new ApiError({ status: 401, title: 'Потрібна автентифікація' })));

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('rejected=1'));
  });

  it('на публічних сторінках 401 нічого не робить', () => {
    renderAt('/changes');
    act(() => mocks.handler?.(new ApiError({ status: 401, title: 'Потрібна автентифікація' })));
    expect(mocks.removeUser).not.toHaveBeenCalled();
    expect(screen.getByTestId('location')).toHaveTextContent('/changes');
  });
});
