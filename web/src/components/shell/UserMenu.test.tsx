import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/mocks/server';
import { useThemeStore } from '@/stores/theme';
import type * as UserManagerModule from '@/auth/userManager';
import { renderWithProviders } from '@/test/render';
import { UserMenu } from './UserMenu';

const current = vi.hoisted(() => ({ isTeacher: true, isAdmin: false }));
const renewSession = vi.hoisted(() => vi.fn(() => Promise.resolve()));

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
  renewSession,
}));

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

async function openMenu() {
  const user = userEvent.setup();
  renderWithProviders(<UserMenu />, { route: '/me' });
  await user.click(screen.getByRole('button', { name: 'Обліковий запис: Кренцін Максим Дмитрович' }));
  await screen.findByRole('menu');
  return user;
}

describe('меню облікового запису', () => {
  // вікно пароля — окрема лінива частина коду: перше перетворення модуля (zod, react-hook-form) у vitest
  // повільне, тож прогріваємо його заздалегідь, щоб findBy не впирався в тайм-аут
  beforeAll(async () => {
    await import('./ChangePasswordDialog');
  });

  beforeEach(() => {
    current.isTeacher = true;
    current.isAdmin = false;
    renewSession.mockReset();
    renewSession.mockImplementation(() => Promise.resolve());
    useThemeStore.getState().setPreference('system');
  });

  it('у бічній панелі назва кнопки починається з видимих імені й ролі (WCAG 2.5.3), у шапці — «Обліковий запис»', () => {
    renderWithProviders(
      <>
        <UserMenu />
        <UserMenu variant="block" />
      </>,
      { route: '/me' },
    );
    expect(screen.getByRole('button', { name: 'Обліковий запис: Кренцін Максим Дмитрович' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Кренцін Максим Дмитрович, Викладач. Обліковий запис' })).toBeInTheDocument();
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

  it('без пароля (вхід лише через Google) пункт — «Встановити пароль»', async () => {
    server.use(
      http.get('*/auth/api/account/password', () =>
        HttpResponse.json({ status: 'NO_PASSWORD', minLength: 12, maxLength: 64, reauthRequired: true }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<UserMenu />, { route: '/me' });
    await user.click(screen.getByRole('button', { name: 'Обліковий запис: Кренцін Максим Дмитрович' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Встановити пароль' }));
    expect(screen.queryByRole('menuitem', { name: 'Змінити пароль' })).toBeNull();
    expect(await screen.findByRole('dialog', { name: 'Встановити пароль' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Підтвердити через Google' })).toBeInTheDocument();
  });

  it('повернення з Google (?password=set) знову відкриває вікно й прибирає позначку з адреси', async () => {
    server.use(
      http.get('*/auth/api/account/password', () =>
        HttpResponse.json({ status: 'NO_PASSWORD', minLength: 12, maxLength: 64, reauthRequired: false }),
      ),
    );
    renderWithProviders(
      <>
        {/* меню буває двічі (шапка й бічна панель) — вікно має відкритися один раз */}
        <UserMenu />
        <UserMenu variant="block" />
        <LocationProbe />
      </>,
      { route: '/me?week=2&password=set' },
    );
    expect(await screen.findByRole('dialog', { name: 'Встановити пароль' })).toBeInTheDocument();
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(await screen.findByLabelText(/^Новий пароль/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/me?week=2'));
    expect(screen.getByTestId('location')).not.toHaveTextContent('password');
    // повторний вхід дав сесії новий id: токени оновлено один раз (інакше «Вийти» не завершило б сесію)
    expect(renewSession).toHaveBeenCalledOnce();
  });

  it('після повернення з Google вікно відкривається лише після тихого оновлення токенів і читає стан заново', async () => {
    let finishRenew: () => void = () => undefined;
    renewSession.mockImplementation(() => new Promise<void>((done) => (finishRenew = done)));
    let statusReads = 0;
    server.use(
      http.get('*/auth/api/account/password', () => {
        statusReads += 1;
        return HttpResponse.json({ status: 'NO_PASSWORD', minLength: 12, maxLength: 64, reauthRequired: false });
      }),
    );
    renderWithProviders(<UserMenu />, { route: '/me?password=set' });
    await waitFor(() => expect(renewSession).toHaveBeenCalledOnce());
    await waitFor(() => expect(statusReads).toBe(1));
    expect(screen.queryByRole('dialog')).toBeNull();

    finishRenew();
    expect(await screen.findByRole('dialog', { name: 'Встановити пароль' })).toBeInTheDocument();
    // стан пароля прочитано вже з новими токенами
    await waitFor(() => expect(statusReads).toBeGreaterThanOrEqual(2));
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
