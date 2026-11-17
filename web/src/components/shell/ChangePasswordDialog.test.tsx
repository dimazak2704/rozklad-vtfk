import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { pageNavigation } from '@/api/account';
import { notify } from '@/components/ui';
import { MOCK_CURRENT_PASSWORD } from '@/mocks/handlers/account';
import { server } from '@/mocks/server';
import type * as UserManagerModule from '@/auth/userManager';
import { renderWithProviders } from '@/test/render';
import { ChangePasswordDialog } from './ChangePasswordDialog';

const token = vi.hoisted(() => {
  const state: { value: string | null } = { value: 'test-access-token' };
  return state;
});

vi.mock('@/auth/userManager', async (importOriginal) => ({
  ...(await importOriginal<typeof UserManagerModule>()),
  getAccessToken: () => Promise.resolve(token.value),
}));

const PATH = '*/auth/api/account/password';
const INITIAL_PATH = '*/auth/api/account/initial-password';

function googleOnly(reauthRequired: boolean) {
  return { status: 'NO_PASSWORD', minLength: 12, maxLength: 64, reauthRequired };
}
const NEW = 'Novyi-parol-2026';

function renderDialog() {
  const onOpenChange = vi.fn();
  const user = userEvent.setup();
  renderWithProviders(<ChangePasswordDialog open onOpenChange={onOpenChange} username="krentsin" />);
  return { user, onOpenChange };
}

async function fill(user: ReturnType<typeof userEvent.setup>, current: string, next: string, repeat = next) {
  await user.type(await screen.findByLabelText(/^Поточний пароль/), current);
  await user.type(screen.getByLabelText(/^Новий пароль/), next);
  await user.type(screen.getByLabelText(/^Повторіть новий пароль/), repeat);
  await user.click(screen.getByRole('button', { name: 'Змінити пароль' }));
}

function authProblem(status: number, code: string, detail: string) {
  return HttpResponse.json(
    { type: `urn:rozklad:auth:${code}`, status, detail, code },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

describe('зміна власного пароля', () => {
  afterEach(() => {
    token.value = 'test-access-token';
    vi.restoreAllMocks();
  });

  it('успіх: сповіщення, що інші пристрої вийдуть, і вікно закривається; запит несе токен і cookie сесії', async () => {
    const success = vi.spyOn(notify, 'success');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    let seen: { authorization: string | null; body: unknown } | null = null;
    server.use(
      http.post(PATH, async ({ request }) => {
        seen = { authorization: request.headers.get('Authorization'), body: await request.json() };
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, onOpenChange } = renderDialog();
    expect(await screen.findByText('Щонайменше 12 символів')).toBeInTheDocument();
    await fill(user, MOCK_CURRENT_PASSWORD, NEW);

    await vi.waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    expect(success).toHaveBeenCalledWith('Пароль змінено', { description: 'На інших пристроях доведеться увійти знову.' });
    expect(seen).toEqual({
      authorization: 'Bearer test-access-token',
      body: { currentPassword: MOCK_CURRENT_PASSWORD, newPassword: NEW },
    });
    // cookie сесії входу каже сервісу, яку сесію лишити (на Vite 5180 → стенд 8088 — інше походження)
    const post = fetchSpy.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(post?.[0]).toMatch(/\/auth\/api\/account\/password$/);
    expect(post?.[1]?.credentials).toBe('include');
  });

  it('неправильний поточний пароль — помилка під цим полем, вікно лишається', async () => {
    const { user, onOpenChange } = renderDialog();
    await fill(user, 'not-my-password', NEW);
    expect(await screen.findByText('Поточний пароль неправильний')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Поточний пароль/)).toHaveAttribute('aria-invalid', 'true');
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('пароль, який відхилили правила сервісу входу, — пояснення сервера під новим паролем', async () => {
    const { user } = renderDialog();
    await fill(user, MOCK_CURRENT_PASSWORD, 'password1234');
    expect(await screen.findByText('Цей пароль надто поширений. Придумайте інший.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Новий пароль/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('форма сама ловить короткий пароль, неоднаковий повтор і той самий пароль', async () => {
    const posted = vi.fn();
    server.use(
      http.post(PATH, () => {
        posted();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = renderDialog();
    await fill(user, MOCK_CURRENT_PASSWORD, 'short-1');
    // підказка під полем і та сама помилка
    expect(await screen.findAllByText('Щонайменше 12 символів')).toHaveLength(2);
    expect(screen.getByLabelText(/^Новий пароль/)).toHaveAttribute('aria-invalid', 'true');

    await user.clear(screen.getByLabelText(/^Новий пароль/));
    await user.type(screen.getByLabelText(/^Новий пароль/), NEW);
    await user.clear(screen.getByLabelText(/^Повторіть новий пароль/));
    await user.type(screen.getByLabelText(/^Повторіть новий пароль/), `${NEW}x`);
    await user.click(screen.getByRole('button', { name: 'Змінити пароль' }));
    expect(await screen.findByText('Паролі не збігаються')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/^Новий пароль/));
    await user.type(screen.getByLabelText(/^Новий пароль/), MOCK_CURRENT_PASSWORD);
    await user.clear(screen.getByLabelText(/^Повторіть новий пароль/));
    await user.type(screen.getByLabelText(/^Повторіть новий пароль/), MOCK_CURRENT_PASSWORD);
    await user.click(screen.getByRole('button', { name: 'Змінити пароль' }));
    expect(await screen.findByText('Новий пароль має відрізнятися від поточного')).toBeInTheDocument();
    expect(posted).not.toHaveBeenCalled();
  });

  it('забагато невдалих спроб — пояснення над кнопками', async () => {
    server.use(http.post(PATH, () => authProblem(429, 'too-many-attempts', 'Забагато невдалих спроб.')));
    const { user } = renderDialog();
    await fill(user, 'not-my-password', NEW);
    expect(await screen.findByRole('alert')).toHaveTextContent('Забагато невдалих спроб. Зачекайте кілька хвилин');
  });

  it('без токена (сесія входу скінчилася) запит не йде, людина бачить, що треба увійти', async () => {
    token.value = null;
    server.use(http.get(PATH, () => HttpResponse.json({ status: 'CHANGEABLE', minLength: 12, maxLength: 64 })));
    renderDialog();
    // стан теж читається з токеном: без нього — помилка завантаження, форми немає
    expect(await screen.findByText('Потрібно увійти в систему.')).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Поточний пароль/)).toBeNull();
  });

  it('вхід лише через Google без свіжого підтвердження — «Встановити пароль» веде на Google і назад сюди', async () => {
    const assign = vi.spyOn(pageNavigation, 'assign').mockImplementation(() => undefined);
    server.use(http.get(PATH, () => HttpResponse.json(googleOnly(true))));
    const { user } = renderDialog();

    expect(await screen.findByRole('dialog', { name: 'Встановити пароль' })).toBeInTheDocument();
    expect(screen.getByText(/Ви входите через Google, пароля поки немає/)).toBeInTheDocument();
    // без підтвердження форми немає: одного токена сервісу входу замало
    expect(screen.queryByLabelText(/^Новий пароль/)).toBeNull();
    expect(screen.queryByLabelText(/^Поточний пароль/)).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Підтвердити через Google' }));

    expect(assign).toHaveBeenCalledTimes(1);
    const target = new URL(assign.mock.calls[0]![0]);
    expect(target.pathname).toMatch(/\/auth\/oauth2\/authorization\/google$/);
    const back = new URL(target.searchParams.get('reauth_return')!);
    expect(back.origin).toBe(window.location.origin);
    expect(back.searchParams.get('password')).toBe('set');
  });

  it('повернулися з Google, а підтвердження немає — вікно каже про це й пропонує ще раз', async () => {
    server.use(http.get(PATH, () => HttpResponse.json(googleOnly(true))));
    renderWithProviders(<ChangePasswordDialog open onOpenChange={vi.fn()} returnedFromGoogle />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Вхід через Google не підтверджено');
    expect(screen.getByRole('button', { name: 'Підтвердити через Google' })).toBeInTheDocument();
  });

  it('після свіжого підтвердження через Google — пароль без поточного; успіх закриває вікно', async () => {
    const success = vi.spyOn(notify, 'success');
    let seen: { authorization: string | null; body: unknown } | null = null;
    server.use(
      http.get(PATH, () => HttpResponse.json(googleOnly(false))),
      http.post(INITIAL_PATH, async ({ request }) => {
        seen = { authorization: request.headers.get('Authorization'), body: await request.json() };
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, onOpenChange } = renderDialog();

    expect(await screen.findByRole('dialog', { name: 'Встановити пароль' })).toBeInTheDocument();
    expect(await screen.findByText(/Вхід через Google підтверджено/)).toHaveTextContent('krentsin');
    expect(screen.queryByLabelText(/^Поточний пароль/)).toBeNull();
    await user.type(screen.getByLabelText(/^Новий пароль/), NEW);
    await user.type(screen.getByLabelText(/^Повторіть пароль/), NEW);
    await user.click(screen.getByRole('button', { name: 'Встановити пароль' }));

    await vi.waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    expect(seen).toEqual({ authorization: 'Bearer test-access-token', body: { newPassword: NEW } });
    expect(success).toHaveBeenCalledWith('Пароль встановлено', {
      description: 'Тепер можна входити і через Google, і з паролем. На інших пристроях доведеться увійти знову.',
    });
  });

  it('підтвердження застаріло, поки людина вводила пароль, — вікно знову просить Google', async () => {
    let fresh = true;
    server.use(
      http.get(PATH, () => HttpResponse.json(googleOnly(!fresh))),
      http.post(INITIAL_PATH, () => {
        fresh = false;
        return authProblem(403, 'reauth-required', 'Підтвердьте вхід через Google ще раз.');
      }),
    );
    const { user, onOpenChange } = renderDialog();
    await user.type(await screen.findByLabelText(/^Новий пароль/), NEW);
    await user.type(screen.getByLabelText(/^Повторіть пароль/), NEW);
    await user.click(screen.getByRole('button', { name: 'Встановити пароль' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Підтвердження через Google застаріло');
    expect(screen.getByRole('button', { name: 'Підтвердити через Google' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Новий пароль/)).toBeNull();
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('перший пароль, який відхилили правила сервісу входу, — пояснення під полем', async () => {
    server.use(
      http.get(PATH, () => HttpResponse.json(googleOnly(false))),
      http.post(INITIAL_PATH, () =>
        HttpResponse.json(
          { type: 'urn:rozklad:auth:password-rejected', status: 400, code: 'password-rejected', reason: 'too-common', detail: 'Цей пароль надто поширений. Придумайте інший.' },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const { user } = renderDialog();
    await user.type(await screen.findByLabelText(/^Новий пароль/), 'password1234');
    await user.type(screen.getByLabelText(/^Повторіть пароль/), 'password1234');
    await user.click(screen.getByRole('button', { name: 'Встановити пароль' }));
    expect(await screen.findByText('Цей пароль надто поширений. Придумайте інший.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Новий пароль/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('спільний демо-доступ — пароль не змінюється', async () => {
    server.use(http.get(PATH, () => HttpResponse.json({ status: 'DEMO', minLength: 12, maxLength: 64 })));
    renderDialog();
    expect(await screen.findByText(/спільний демонстраційний доступ/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Поточний пароль/)).toBeNull();
  });
});
