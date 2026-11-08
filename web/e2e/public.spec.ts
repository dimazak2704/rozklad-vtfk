import { expect, test, type Page } from '@playwright/test';

// Dev-сервер компілює модулі на льоту: перший запуск сторінки буває повільним
test.describe.configure({ timeout: 90_000 });

/** Понеділок 09.11.2026, 10:05 у Києві: іде друга пара, розпорядження на сьогодні опубліковане. */
const NOW = new Date('2026-11-09T08:05:00Z');

async function open(page: Page, path: string): Promise<void> {
  await page.clock.setFixedTime(NOW);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 30_000 });
}

test.describe('публічний розклад', () => {
  test('пошук «4ki1» → розклад групи → день і тиждень → деталі заміни', async ({ page, isMobile }) => {
    await open(page, '/');
    const search = page.getByRole('combobox', { name: /Знайти розклад/ });
    await search.fill('4ki1');
    await expect(page.getByRole('option', { name: /4КІ1/ }).first()).toBeVisible();
    await search.press('Enter');

    await expect(page).toHaveURL(/\/g\/4%D0%9A%D0%861/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Розклад групи\s*4КІ1/);

    // типове подання залежить від ширини: на телефоні — день, на десктопі — тиждень
    const views = page.getByRole('radiogroup', { name: 'Подання розкладу' });
    const week = views.getByRole('radio', { name: 'Тиждень' });
    const day = views.getByRole('radio', { name: 'День', exact: true });
    await week.click();
    await expect(week).toBeChecked();

    await day.click();
    await expect(page).toHaveURL(/view=day/);
    await expect(page.getByRole('radiogroup', { name: 'День тижня' })).toBeVisible();

    await week.click();
    await expect(page).toHaveURL(/view=week/);
    if (!isMobile) await expect(page.getByRole('table').filter({ hasText: 'Понеділок' })).toBeVisible();

    // тиждень розпорядження №35: СТП Кренціна замінено на НДЕКСМ Непийводи («Зв.зам.»)
    await page.goto('/g/4%D0%9A%D0%861?view=week&date=2026-10-19');
    await expect(page.getByRole('region', { name: 'Зміни цього тижня' })).toContainText('НДЕКСМ, Непийвода замість СТП, Кренцін');
    const lesson = page.getByRole('button', { name: /^2 пара, 09:40–10:40\. Надійність/ });
    if (isMobile) await lesson.click();
    else await lesson.hover();
    const details = page.getByRole('dialog', { name: /Деталі: НДЕКСМ, 2 пара/ });
    await expect(details).toBeVisible();
    // публічна картка показує причину словами, не скороченням із розпорядження
    await expect(details).toContainText('Причина: взаємна заміна');
    await expect(details).toContainText('СТП, Кренцін');
  });

  test('невідома група пропонує схожі коди', async ({ page }) => {
    await open(page, '/g/4%D0%9A%D0%869');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Групи «4КІ9» немає в розкладі');
    await expect(page.getByRole('link', { name: '4КІ1' })).toBeVisible();
  });
});

test.describe('заміни', () => {
  test('фільтр за групою, курсом і «лише мої»', async ({ page }) => {
    await open(page, '/changes?date=2026-10-19');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('19 жовтня');
    await expect(page.getByText('Розпорядження №35', { exact: false })).toBeVisible();
    const group = (code: string) => page.getByRole('link', { name: new RegExp(`^${code}`) });
    for (const code of ['1КІ2', '1ТК1', '4КІ1']) await expect(group(code)).toBeVisible();

    await page.getByRole('textbox', { name: 'Знайти групу' }).fill('4ki1');
    await expect(group('4КІ1')).toBeVisible();
    await expect(group('1КІ2')).toBeHidden();
    await expect(page.getByText('НДЕКСМ, Непийвода').filter({ visible: true }).first()).toBeVisible();

    await page.getByRole('button', { name: 'Очистити пошук групи' }).click();
    await page.getByRole('button', { name: '1 курс' }).click();
    await expect(group('1КІ2')).toBeVisible();
    await expect(group('1ТК1')).toBeVisible();
    await expect(group('4КІ1')).toBeHidden();
    await expect(page).toHaveURL(/course=1/);
    await page.getByRole('button', { name: '1 курс' }).click();

    // «Лише мої» з'являється, щойно в обраному є група
    await expect(page.getByRole('button', { name: 'Лише мої' })).toBeDisabled();
    await page.goto('/g/4%D0%9A%D0%861?date=2026-10-19');
    await page.getByRole('button', { name: 'Додати 4КІ1 в обране' }).click();
    await page.goto('/changes?date=2026-10-19');
    await page.getByRole('button', { name: 'Лише мої' }).click();
    await expect(group('4КІ1')).toBeVisible();
    await expect(group('1КІ2')).toBeHidden();
    await expect(group('1ТК1')).toBeHidden();
  });

  test('дата без розпорядження — зрозумілий порожній стан', async ({ page }) => {
    await open(page, '/changes?date=2026-10-05');
    await expect(page.getByText('Змін не було')).toBeVisible();
  });
});
