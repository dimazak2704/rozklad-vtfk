import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useThemeStore } from '@/stores/theme';
import type * as UserManagerModule from '@/auth/userManager';
import { renderWithProviders } from '@/test/render';
import { UserMenu } from './UserMenu';

const current = vi.hoisted(() => ({ isTeacher: true, isAdmin: false }));

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({
    status: 'authenticated',
    user: { name: 'Кренцін Максим Дмитрович', username: 'krentsin' },
    isAdmin: current.isAdmin,
    isTeacher: current.isTeacher,
    isDevBypass: false,
    signOut: () => undefined,
  }),
}));

vi.mock('@/auth/userManager', async (importOriginal) => ({
  ...(await importOriginal<typeof UserManagerModule>()),
  getAccessToken: () => Promise.resolve('test-access-token'),
}));

async function openMenu() {
  const user = userEvent.setup();
  renderWithProviders(<UserMenu />, { route: '/me' });
  await user.click(screen.getByRole('button', { name: 'Обліковий запис: Кренцін Максим Дмитрович' }));
  await screen.findByRole('menu');
  return user;
}

describe('меню облікового запису', () => {
  beforeEach(() => {
    current.isTeacher = true;
    current.isAdmin = false;
    useThemeStore.getState().setPreference('system');
  });

  it('тема — один пункт із підменю: три варіанти, поточний позначено, усе з клавіатури', async () => {
    const user = await openMenu();
    // замість трьох окремих пунктів у самому меню — один «Тема» з поточним вибором
    expect(screen.queryByRole('menuitemradio')).toBeNull();
    const theme = screen.getByRole('menuitem', { name: /Тема/ });
    expect(theme).toHaveAttribute('aria-haspopup', 'menu');
    expect(theme).toHaveTextContent('як у системі');

    theme.focus();
    await user.keyboard('{ArrowRight}');
    const radios = await screen.findAllByRole('menuitemradio');
    expect(radios.map((item) => item.textContent)).toEqual(['Як у системі', 'Світла', 'Темна']);
    expect(radios[0]).toHaveAttribute('aria-checked', 'true');
    await waitFor(() => expect(radios[0]).toHaveFocus());

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');
    expect(useThemeStore.getState().preference).toBe('dark');
  });

  it('викладач бачить «Змінити пароль»; пункт відкриває вікно', async () => {
    const user = await openMenu();
    await user.click(screen.getByRole('menuitem', { name: 'Змінити пароль' }));
    expect(await screen.findByRole('dialog', { name: 'Змінити пароль' })).toBeInTheDocument();
    expect(await screen.findByLabelText(/Поточний пароль/)).toHaveAttribute('autocomplete', 'current-password');
  });

  it('диспетчер теж може змінити свій пароль', async () => {
    current.isTeacher = false;
    current.isAdmin = true;
    await openMenu();
    expect(screen.getByRole('menuitem', { name: 'Змінити пароль' })).toBeInTheDocument();
  });

  it('обліковий запис без ролі персоналу пароля тут не змінює', async () => {
    current.isTeacher = false;
    await openMenu();
    expect(screen.queryByRole('menuitem', { name: 'Змінити пароль' })).toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Вийти' })).toBeInTheDocument();
  });
});
