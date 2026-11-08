import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';

const signIn = vi.hoisted(() => vi.fn<(returnTo?: string) => Promise<void>>());

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({ status: 'anonymous', roles: [], error: null, signIn }),
}));

describe('LoginPage', () => {
  it('сервер входу недоступний: пояснення українською, без «Failed to fetch»', async () => {
    signIn.mockRejectedValue(new TypeError('Failed to fetch'));
    render(
      <MemoryRouter initialEntries={['/login?returnTo=%2Fadmin']}>
        <LoginPage />
      </MemoryRouter>,
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Немає звʼязку із сервером входу коледжу. Перевірте, чи працює сервер входу, і спробуйте ще раз.');
    expect(alert).not.toHaveTextContent('Failed to fetch');
    expect(signIn).toHaveBeenCalledWith('/admin');
    expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeEnabled();
  });
});
