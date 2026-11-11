import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';

const signIn = vi.hoisted(() => vi.fn<(returnTo?: string) => Promise<void>>());
const auth = vi.hoisted((): { error: unknown } => ({ error: null }));

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({ status: 'anonymous', roles: [], error: auth.error, signIn }),
}));

function renderLogin() {
  render(
    <MemoryRouter initialEntries={['/login?returnTo=%2Fadmin']}>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  it('сервер входу недоступний: пояснення українською, без «Failed to fetch»', async () => {
    auth.error = null;
    signIn.mockRejectedValue(new TypeError('Failed to fetch'));
    renderLogin();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Немає звʼязку із сервером входу коледжу. Перевірте, чи працює сервер входу, і спробуйте ще раз.');
    expect(alert).not.toHaveTextContent('Failed to fetch');
    expect(signIn).toHaveBeenCalledWith('/admin');
    expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeEnabled();
  });

  it('після невдалого фонового оновлення (сесії немає) просто веде на вхід, без помилки', async () => {
    auth.error = { name: 'ErrorResponse', message: 'login_required', source: 'renewSilent' };
    signIn.mockReturnValue(new Promise(() => undefined));
    renderLogin();
    await vi.waitFor(() => expect(signIn).toHaveBeenCalledWith('/admin'));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('Відкриваємо сторінку входу коледжу…')).toBeInTheDocument();
  });
});
