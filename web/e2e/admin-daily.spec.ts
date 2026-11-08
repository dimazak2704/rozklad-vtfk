import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

/**
 * Щоденна робота диспетчера на мок-API: відсутність → кандидат → підтвердження в Telegram → розпорядження → публікація.
 * Годинник зафіксовано на понеділок 09.11.2026, 07:40 за Києвом — на цей день припадає демо-ранок мок-даних.
 * Відповіді викладачів у Telegram не імітуються самі (rozklad.mockTelegram = manual): тест надсилає відповідь так,
 * як це робить бот, — POST /api/internal/changes/{id}/reply.
 */
test.describe.configure({ timeout: 120_000 });

const NOW = new Date('2026-11-09T05:40:00Z');
const DAY = '2026-11-09';

async function openAsDispatcher(page: Page, path: string): Promise<void> {
  await loginWithMockRoles(page, 'ADMIN');
  await page.addInitScript(() => window.sessionStorage.setItem('rozklad.mockTelegram', 'manual'));
  await page.clock.setFixedTime(NOW);
  await page.goto(path);
}

/** Відповідь викладача в Telegram — так, як її передає бот у core. */
async function replyInTelegram(page: Page, groupCode: string, decision: 'CONFIRM' | 'DECLINE'): Promise<void> {
  await page.evaluate(
    async ({ date, groupCode: code, decision: answer }) => {
      const list = (await (await fetch(`/api/admin/changes?date=${date}&status=PENDING_CONFIRMATION`)).json()) as Array<{
        id: string;
        group: { code: string };
        new: { teacher: { id: string } | null };
      }>;
      const change = list.find((c) => c.group.code === code);
      if (!change?.new.teacher) throw new Error(`Немає заміни в очікуванні для ${code}`);
      const response = await fetch(`/api/internal/changes/${change.id}/reply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teacherId: change.new.teacher.id, decision: answer }),
      });
      if (!response.ok) throw new Error(`Бот отримав ${response.status}`);
    },
    { date: DAY, groupCode, decision },
  );
}

test.describe('щоденна робота диспетчера', () => {
  test.skip(({ isMobile }) => isMobile, 'Панель диспетчера розрахована на планшет і більше');

  test('відсутність → найкращий кандидат → підтвердження в Telegram → рядок у розпорядженні → публікація №36', async ({ page }) => {
    await openAsDispatcher(page, '/admin/changes');
    await expect(page.getByRole('heading', { level: 1, name: 'Заміни' })).toBeVisible({ timeout: 30_000 });

    // 1. Нова відсутність: Погосян на весь день (пари 6 і 7 у групах 2ВЕ і 2ЕА1)
    await page.getByRole('button', { name: /Нова відсутність/ }).first().click();
    const form = page.getByRole('dialog', { name: 'Нова відсутність' });
    await form.getByRole('combobox', { name: 'Викладач' }).click();
    await page.getByPlaceholder('Прізвище, напр. «кренц»').fill('погосян');
    await page.getByRole('option', { name: 'Погосян' }).click();
    await expect(form.getByText('Потрібні рішення для 2')).toBeVisible();
    await form.getByRole('button', { name: 'Додати відсутність' }).click();
    await expect(form).toBeHidden();
    await expect(page.getByRole('heading', { level: 3, name: 'Погосян' })).toBeVisible();

    // 2. Перша пара відсутнього вже відкрита — обираємо викладача іншої дисципліни групи; вибір ще нічого не надсилає
    const lesson = page.getByRole('button', { name: /2ВЕ/ }).filter({ hasText: 'Потрібне рішення' });
    await expect(lesson).toHaveAttribute('aria-current', 'true');
    // мок працює в режимі CONFIRM: дія над обраним варіантом надсилає викладачу прохання
    const options = page.getByRole('list', { name: 'Варіанти' });
    const best = options.locator('button[aria-pressed]').filter({ hasText: 'Інша дисципліна групи' }).first();
    const title = (await best.locator('span.text-ui').textContent()) ?? '';
    const candidate = title.split(' — ')[1] ?? '';
    expect(candidate).not.toBe('');
    await best.click();
    await expect(lesson).toContainText('Потрібне рішення');
    await options.getByRole('button', { name: 'Надіслати прохання' }).click();

    // 3. Заміна чекає підтвердження в Telegram
    const decision = page.getByRole('region', { name: 'Рішення щодо пари' });
    await expect(decision.getByText('Чекаємо відповіді в Telegram', { exact: false })).toBeVisible();
    await expect(decision.getByText('Чекає відповіді')).toBeVisible();

    // 4. Викладач підтверджує в боті — картка оновлюється наживо, зʼявляється тост
    await replyInTelegram(page, '2ВЕ', 'CONFIRM');
    await expect(page.getByText(`Підтверджено в Telegram: ${candidate}`)).toBeVisible({ timeout: 15_000 });
    // і бейдж стану, і остання зупинка історії кажуть «Підтверджено»
    await expect(decision.getByText('Підтверджено', { exact: true }).first()).toBeVisible();

    // 5. Рядок уже в чернетці розпорядження (перехід без перезавантаження: стан мок-API живе в сторінці)
    await decision.getByRole('link', { name: 'Переглянути чернетку' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Розпорядження' })).toBeVisible();
    const sheet = page.getByRole('article', { name: /Розпорядження № ____.*чернетка/ });
    const row = sheet.getByRole('row').filter({ hasText: '2ВЕ' });
    // у розпорядженні — коротке імʼя, як друкує коледж («Мельник», а не «Мельник Н.М.»)
    await expect(row).toContainText(candidate.split(' ')[0] ?? candidate);
    // інша дисципліна групи друкується як взаємна заміна
    await expect(row).toContainText('Зв.зам.');

    // 6. Публікація: діалог показує номер, після публікації — №36 у документі й файли
    await page.getByRole('button', { name: 'Опублікувати №36' }).click();
    const confirm = page.getByRole('dialog', { name: 'Опублікувати розпорядження?' });
    await expect(confirm.getByText('№36', { exact: true })).toBeVisible();
    await confirm.getByRole('button', { name: 'Опублікувати №36' }).click();
    await expect(page.getByText('Розпорядження №36 опубліковано')).toBeVisible();
    await expect(page.getByRole('article', { name: /Розпорядження №36 на 9\sлистопада 2026/ })).toBeVisible();

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Завантажити DOCX' }).click();
    expect((await download).suggestedFilename()).toBe('Розпорядження №36 від 09.11.2026.docx');
  });

  test('панель: маршрут ранку веде до пар без рішення', async ({ page }) => {
    await openAsDispatcher(page, '/admin');
    await expect(page.getByRole('heading', { level: 1, name: 'Панель диспетчера' })).toBeVisible({ timeout: 30_000 });
    const route = page.getByRole('navigation', { name: /Маршрут ранку/ });
    const stop = route.getByRole('link', { name: /Без рішення/ });
    await expect(stop).toContainText('4');
    await expect(stop).toHaveAttribute('aria-current', 'step');
    await stop.click();
    await expect(page).toHaveURL(/\/admin\/changes\?.*filter=uncovered/);
    await expect(page.getByRole('button', { name: /Без рішення/, pressed: true })).toBeVisible();
  });

  test('Telegram: код привʼязки чату групи з посиланням і QR', async ({ page }) => {
    await openAsDispatcher(page, '/admin/telegram');
    await expect(page.getByRole('heading', { level: 1, name: 'Telegram' })).toBeVisible({ timeout: 30_000 });
    await page.getByRole('button', { name: 'Код привʼязки' }).click();
    const dialog = page.getByRole('dialog', { name: 'Новий код привʼязки' });
    await dialog.getByRole('combobox', { name: 'Група' }).click();
    await page.getByPlaceholder('Код групи, напр. «4ki1»').fill('4ki1');
    await page.getByRole('option', { name: /4КІ1/ }).first().click();
    await dialog.getByRole('button', { name: 'Створити код' }).click();
    const result = page.getByRole('dialog', { name: 'Код готовий' });
    await expect(result.getByRole('link', { name: /t\.me\/scheduler_vtfk_bot\?startgroup=/ })).toBeVisible();
    await expect(result.locator('svg title', { hasText: 'QR-код' })).toBeAttached();
  });

  test('налаштування: помилка біля поля, межі числа тримає саме поле, збереження', async ({ page }) => {
    await openAsDispatcher(page, '/admin/settings');
    await expect(page.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeVisible({ timeout: 30_000 });
    const position = page.getByRole('textbox', { name: 'Посада підписанта' });
    await position.fill('');
    await position.blur();
    await expect(page.getByText('Вкажіть посаду підписанта')).toBeVisible();
    await position.fill('Заступник директора з навчальної роботи');
    // поле числа не пропускає значення поза межами: 2 хв стає мінімальними 5
    const timeout = page.getByRole('spinbutton', { name: 'Скільки чекати відповіді' });
    await timeout.fill('2');
    await timeout.blur();
    await expect(timeout).toHaveValue('5');
    await timeout.fill('20');
    await timeout.blur();
    await page.getByRole('button', { name: 'Зберегти' }).click();
    await expect(page.getByText('Налаштування збережено')).toBeVisible();
    await expect(page.getByText('Є незбережені зміни')).toBeHidden();
  });
});

test('панель на телефоні: без горизонтальної прокрутки', async ({ page, isMobile }) => {
  test.skip(!isMobile, 'Перевірка вузького екрана');
  await openAsDispatcher(page, '/admin');
  await expect(page.getByRole('heading', { level: 1, name: 'Панель диспетчера' })).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(800);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
