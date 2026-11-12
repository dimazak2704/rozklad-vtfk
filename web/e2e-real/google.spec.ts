import { randomBytes } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import { AUTH_URL } from './support/tokens';
import { apiAs, GATEWAY, isSignInPage, login } from './support/real';

/**
 * «Увійти через Google» проти mock-oauth2-server (scripts/dev-stack.sh up --google-mock, docs/GOOGLE_SIGNIN.md):
 * дозволена адреса входить, невідома бачить точне повідомлення, а після «Відкликати доступ» у «Доступах»
 * тихе оновлення входу вже не вдається і старе запрошення відповідає «Доступ відкликано».
 */
const MOCK = process.env.REAL_GOOGLE_MOCK_URL || 'http://localhost:8090/google';
const NOT_ALLOWED = 'Цієї адреси немає у списку доступу. Зверніться до навчальної частини.';

async function mockRunning(): Promise<boolean> {
  try {
    return (await fetch(`${MOCK}/.well-known/openid-configuration`)).ok;
  } catch {
    return false;
  }
}

/** Сторінка входу → «Увійти через Google» → сторінка mock-сервера (ім'я = sub, claims з e-mail). */
async function signInWithGoogle(page: Page, email: string, subject = `e2e:${email}`): Promise<void> {
  await page.goto('/login');
  await page.waitForURL(isSignInPage, { timeout: 30_000 });
  await page.getByRole('link', { name: 'Увійти через Google' }).click();
  await page.waitForURL((url) => url.href.startsWith(MOCK), { timeout: 30_000 });
  await page.locator('input[name="username"]').fill(subject);
  await page.locator('textarea[name="claims"]').fill(JSON.stringify({ email }));
  await page.getByRole('button', { name: 'Sign-in' }).click();
}

test.describe('вхід через Google (mock)', () => {
  test.beforeEach(async () => {
    test.skip(!(await mockRunning()), 'потрібен scripts/dev-stack.sh up --google-mock');
  });

  test('адреса зі списку доступу входить у кабінет', async ({ page }) => {
    // демо-викладача могли прив'язати до іншого «акаунта Google» вручну: відв'язуємо, перший вхід прив'яже цей
    const admin = await apiAs('ADMIN');
    const unbound = await admin.delete('/api/admin/access/a0000000-0000-4000-8000-000000000002/google');
    expect(unbound.status(), await unbound.text()).toBe(204);
    await signInWithGoogle(page, 'krentsin@rozklad.local');
    await page.waitForURL((url) => url.pathname.startsWith('/me'), { timeout: 30_000 });
  });

  test('невідома адреса бачить точне повідомлення на сторінці входу', async ({ page }) => {
    await signInWithGoogle(page, `student.${randomBytes(3).toString('hex')}@vtc.vn.ua`);
    await page.waitForURL((url) => isSignInPage(url) && url.search === '?error=not_allowed', { timeout: 30_000 });
    await expect(page.getByRole('alert')).toHaveText(NOT_ALLOWED);
  });

  test('після «Відкликати доступ» тихе оновлення не вдається, а старе запрошення каже «Доступ відкликано»', async ({ page, browser, baseURL }) => {
    const admin = await apiAs('ADMIN');
    const suffix = randomBytes(3).toString('hex');
    const teacher = (await (
      await admin.post('/api/admin/teachers', { data: { lastName: `Тестовий${suffix}`, firstName: 'Е2Е' } })
    ).json()) as { id: string };
    const invite = (await (await admin.post('/api/admin/invites', { data: { teacherId: teacher.id } })).json()) as { token: string };
    const email = `e2e.${suffix}@vtc.vn.ua`;
    const granted = await admin.post('/api/admin/access', { data: { email, roles: ['TEACHER'], teacherId: teacher.id } });
    expect(granted.status(), await granted.text()).toBe(201);

    // викладач входить через Google в окремому вікні
    const teacherContext = await browser.newContext({ baseURL, locale: 'uk-UA' });
    const teacherPage = await teacherContext.newPage();
    await signInWithGoogle(teacherPage, email);
    await teacherPage.waitForURL((url) => url.pathname.startsWith('/me'), { timeout: 30_000 });

    // диспетчер відкликає доступ у «Доступах»
    await login(page, 'ADMIN', '/admin/access');
    await page.getByRole('textbox', { name: /Пошук/ }).fill(email);
    const row = page.getByRole('row', { name: new RegExp(email.replace(/\./g, '\\.')) });
    await row.hover();
    await row.getByRole('button', { name: /Дії/ }).click();
    await page.getByRole('menuitem', { name: 'Відкликати доступ' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Відкликати доступ' }).click();
    await expect(row.getByText('Відкликано')).toBeVisible();

    // наступне тихе оновлення (prompt=none з cookie сесії входу) — login_required: сесію видалено
    const renew = await teacherPage.request.get(
      `${AUTH_URL}/oauth2/authorize?response_type=code&client_id=rozklad-web&scope=openid&state=e2e&prompt=none` +
        `&redirect_uri=${encodeURIComponent(`${new URL(teacherPage.url()).origin}/login/callback`)}` +
        '&code_challenge=47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU&code_challenge_method=S256',
      { maxRedirects: 0 },
    );
    expect(renew.headers().location ?? '').toContain('error=login_required');

    // старе запрошення того самого викладача
    const accepted = await fetch(`${GATEWAY}/api/public/invites/${invite.token}/accept`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: `e2e${suffix}`, password: 'Long-enough-pass-1' }),
    });
    expect(accepted.status).toBe(410);
    expect(((await accepted.json()) as { detail: string }).detail).toBe('Доступ відкликано. Зверніться до навчальної частини.');

    await teacherContext.close();
  });
});
