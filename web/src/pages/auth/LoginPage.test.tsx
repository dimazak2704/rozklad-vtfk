import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
    expect(screen.getByRole('status')).toHaveTextContent('Відкриваємо сторінку входу…');
  });

  describe('мить переходу', () => {
    afterEach(() => vi.useRealTimers());

    it('лише знак, рядок і кільце — без великої сторінки з поясненнями й кнопкою', async () => {
      auth.error = null;
      signIn.mockReset();
      signIn.mockReturnValue(new Promise(() => undefined));
      renderLogin();
      await vi.waitFor(() => expect(signIn).toHaveBeenCalledTimes(1));
      expect(screen.getByRole('status')).toHaveTextContent('Відкриваємо сторінку входу…');
      expect(screen.queryByRole('heading')).toBeNull();
      expect(screen.queryByRole('button')).toBeNull();
      expect(screen.queryByText(/Обліковий запис коледжу/)).toBeNull();
    });

    it('перехід затягнувся понад 3 с — пропонує спробувати ще раз, і спроба знову веде на вхід', async () => {
      vi.useFakeTimers();
      auth.error = null;
      signIn.mockReset();
      signIn.mockReturnValue(new Promise(() => undefined));
      renderLogin();
      expect(signIn).toHaveBeenCalledTimes(1);
      await act(() => vi.advanceTimersByTimeAsync(2_900));
      expect(screen.queryByRole('button', { name: 'Спробувати ще раз' })).toBeNull();
      await act(() => vi.advanceTimersByTimeAsync(200));
      expect(screen.getByText('Сторінка входу відкривається довше, ніж зазвичай.')).toBeInTheDocument();
      vi.useRealTimers();
      await userEvent.click(screen.getByRole('button', { name: 'Спробувати ще раз' }));
      expect(signIn).toHaveBeenCalledTimes(2);
      expect(signIn).toHaveBeenLastCalledWith('/admin');
      // нова спроба знову дає повні 3 с, тож підказка ховається
      expect(screen.queryByText('Сторінка входу відкривається довше, ніж зазвичай.')).toBeNull();
    });
  });
});
