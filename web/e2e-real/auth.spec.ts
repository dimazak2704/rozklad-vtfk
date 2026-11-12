import { expect, test, type Page } from '@playwright/test';
import { credentials, fillSignInForm, isSignInPage, login } from './support/real';

/**
 * Вхід через сервіс входу auth на справжньому стенді: сторінка /auth/login, повернення в SPA, вихід,
 * помилки форми і тихе оновлення токена прихованим iframe (без refresh token-а).
 * Однаково проходить із Vite (5180, видавець на 8088) і з nginx-контейнера (REAL_BASE_URL=http://localhost:8088).
 */

/** Ключ sessionStorage, де oidc-client-ts тримає користувача: `oidc.user:<authority>:rozklad-web`. */
async function storedUser(page: Page): Promise<{ access_token: string; expires_at: number } | null> {
  return page.evaluate(() => {
    const key = Object.keys(sessionStorage).find((name) => name.startsWith('oidc.user:'));
    return key ? (JSON.parse(sessionStorage.getItem(key) ?? 'null') as { access_token: string; expires_at: number }) : null;
  });
}

function claims(token: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(token.split('.')[1] ?? '', 'base64url').toString('utf8')) as Record<string, unknown>;
}

test.describe('вхід через сервіс входу', () => {
  test('диспетчер входить, повертається на сторінку, з якої прийшов, і виходить', async ({ page }) => {
    await login(page, 'ADMIN', '/admin/orders');
    await expect(page).toHaveURL(/\/admin\/orders$/);

    const user = await storedUser(page);
    expect(user, 'користувач у sessionStorage').not.toBeNull();
    const access = claims(user?.access_token ?? '');
    expect(access.roles).toEqual(['ADMIN']);
    expect(access.aud).toEqual(expect.arrayContaining(['rozklad-web', 'rozklad-api']));
    expect(access.iss).toBe('http://localhost:8088/auth');
    expect(user).not.toHaveProperty('refresh_token');

    await page.getByRole('button', { name: /^Обліковий запис/ }).first().click();
    await page.getByRole('menuitem', { name: 'Вийти' }).click();
    // вихід завершує сесію сервісу входу: захищена сторінка знову веде на форму, а не входить сама
    await page.waitForURL((url) => url.pathname === '/' && !url.search.includes('code='), { timeout: 30_000 });
    expect(await storedUser(page)).toBeNull();
    await page.goto('/admin');
    await page.waitForURL(isSignInPage, { timeout: 30_000 });
  });

  test('викладач після входу потрапляє в кабінет', async ({ page }) => {
    await login(page, 'TEACHER');
    await expect(page).toHaveURL(/\/me(\/|$)/);
    expect(claims((await storedUser(page))?.access_token ?? '').roles).toEqual(['TEACHER']);
  });

  test('неправильний пароль: одне загальне повідомлення, правильний після нього входить', async ({ page }) => {
    const { username, password } = credentials('ADMIN');
    await page.goto('/login');
    await page.waitForURL(isSignInPage, { timeout: 30_000 });
    await fillSignInForm(page, username, `${password}-wrong`);
    await expect(page.getByRole('alert')).toHaveText('Неправильний логін або пароль.');
    await expect(page.getByLabel('Пароль', { exact: true })).toHaveAttribute('aria-invalid', 'true');
    // успішний вхід скидає лічильник невдалих спроб: повторні запуски тесту не блокують демо-адміна
    await fillSignInForm(page, username, password);
    await page.waitForURL((url) => url.pathname.startsWith('/admin'), { timeout: 30_000 });
  });

  test('після серії невдалих спроб — повідомлення про блокування', async ({ page }) => {
    await page.goto('/login');
    await page.waitForURL(isSignInPage, { timeout: 30_000 });
    // окремий неіснуючий логін: невідомий і наявний обліковий запис блокуються однаково
    const alert = page.getByRole('alert');
    for (let attempt = 0; attempt < 12; attempt++) {
      await fillSignInForm(page, 'e2e-lockout', 'wrong-password-e2e');
      await expect(alert).toBeVisible();
      if ((await alert.textContent())?.includes('Забагато спроб')) break;
      await expect(alert).toHaveText('Неправильний логін або пароль.');
    }
    // з увімкненим Google (--google-mock) текст ще підказує вхід через Google тим, у кого він прив'язаний
    await expect(alert).toHaveText(/^\s*Забагато спроб\. Спробуйте пізніше(\.| або, якщо ваш вхід прив'язано до Google, увійдіть через Google\.)\s*$/);
  });
});

test.describe('тихе оновлення входу', () => {
  // потрібен короткий строк токена: AUTH_ACCESS_TOKEN_TTL=2m у сервісі auth (docs/RUNNING.md)
  test.skip(process.env.REAL_SILENT_RENEW !== '1', 'лише з REAL_SILENT_RENEW=1 і коротким AUTH_ACCESS_TOKEN_TTL');

  test('токен оновлюється прихованим iframe без переходів сторінки', async ({ page }) => {
    await login(page, 'ADMIN', '/admin');
    const before = await storedUser(page);
    expect(before).not.toBeNull();
    const lifetime = (before?.expires_at ?? 0) * 1000 - Date.now();
    test.setTimeout(lifetime + 90_000);
    // позначка у вікні зникла б після перезавантаження сторінки
    await page.evaluate(() => Object.assign(window, { __noReload: true }));
    const url = page.url();

    await expect
      .poll(async () => (await storedUser(page))?.access_token, { timeout: lifetime + 30_000, intervals: [5_000] })
      .not.toBe(before?.access_token);

    const after = await storedUser(page);
    expect(after?.expires_at ?? 0).toBeGreaterThan(before?.expires_at ?? 0);
    expect(claims(after?.access_token ?? '').sub).toBe(claims(before?.access_token ?? '').sub);
    expect(page.url()).toBe(url);
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true);
    // оновлений токен сервери приймають
    const status = await page.evaluate(async (token) => (await fetch('/api/me', { headers: { Authorization: `Bearer ${token}` } })).status,
      after?.access_token ?? '');
    expect(status).toBe(200);
  });
});
