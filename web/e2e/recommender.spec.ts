import { mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

/**
 * Рекомендатор v2 на мок-API, на ноутбуці й телефоні: один список варіантів у порядку сервера, вибір окремо
 * від дії, «Ще варіанти», «Інший викладач…» і план дня з пакетом рішень. Знімки обох тем — у RECSYS_SHOTS_DIR
 * (типово тимчасова тека ОС), щоб переглянути переноси, контраст і відступи.
 */
test.describe.configure({ timeout: 120_000 });

const NOW = new Date('2026-11-09T05:40:00Z');
const DAY = '2026-11-09';
const OUT = process.env.RECSYS_SHOTS_DIR ?? join(tmpdir(), 'rozklad-recsys-ui');
const THEMES = ['light', 'dark'] as const;

async function open(page: Page, theme: (typeof THEMES)[number], lessonId: string | null): Promise<void> {
  await loginWithMockRoles(page, 'ADMIN');
  await page.addInitScript(
    ([preference]) => {
      window.sessionStorage.setItem('rozklad.mockTelegram', 'manual');
      window.localStorage.setItem('rozklad.theme', JSON.stringify({ state: { preference }, version: 1 }));
    },
    [theme] as const,
  );
  await page.clock.setFixedTime(NOW);
  await page.goto(`/admin/changes?date=${DAY}${lessonId ? `&lesson=${lessonId}` : ''}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Заміни' })).toBeVisible({ timeout: 30_000 });
}

/** Перша пара відсутності, повідомленої з кабінету (дві останні пари викладача, ще без рішень). */
async function leavingLessonId(page: Page): Promise<string> {
  return page.evaluate(async (date) => {
    const absences = (await (await fetch(`/api/admin/absences?from=${date}&to=${date}`)).json()) as Array<{ id: string; source: string }>;
    const leaving = absences.find((a) => a.source === 'WEB');
    if (!leaving) throw new Error('У демо-ранку має бути відсутність з кабінету');
    const affected = (await (await fetch(`/api/admin/absences/${leaving.id}/affected`)).json()) as Array<{ lesson: { id: string } }>;
    const first = affected[0];
    if (!first) throw new Error('У відсутності немає пар');
    return first.lesson.id;
  }, DAY);
}

async function shot(page: Page, name: string): Promise<void> {
  // дані мок-API приходять із затримкою; анімації діалогів — до 220 мс
  await page.waitForTimeout(400);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect.soft(overflow, `горизонтальна прокрутка: ${name}`).toBeLessThanOrEqual(0);
  await page.screenshot({ path: join(OUT, `${name}.png`) });
}

/** Панель варіантів: на ноутбуці — бічна колонка, на телефоні — шторка. */
function optionsRegion(page: Page, isMobile: boolean): Locator {
  return isMobile ? page.getByRole('dialog', { name: 'Варіанти рішення' }) : page.getByRole('complementary', { name: 'Варіанти рішення' });
}

test('варіанти: три видно, «Ще варіанти», вибір не надсилає, «Інший викладач…», план дня', async ({ page, isMobile }, info) => {
  mkdirSync(OUT, { recursive: true });
  const device = info.project.name;
  await open(page, 'light', null);
  const lessonId = await leavingLessonId(page);

  for (const theme of THEMES) {
    await open(page, theme, lessonId);
    const region = optionsRegion(page, isMobile);
    const list = region.getByRole('list', { name: 'Варіанти' });
    await expect(list).toBeVisible({ timeout: 15_000 });

    // один список без вкладок і балів; видно не більше трьох
    await expect(region.getByRole('tab')).toHaveCount(0);
    await expect(region.getByRole('meter')).toHaveCount(0);
    const selectors = list.locator('button[aria-pressed]');
    expect(await selectors.count()).toBeLessThanOrEqual(3);
    await shot(page, `${device}-${theme}-1-list`);

    // клік лише обирає: зʼявляється кнопка дії, а пара досі без рішення
    await selectors.first().click();
    await expect(selectors.first()).toHaveAttribute('aria-pressed', 'true');
    const action = list.getByRole('button', { name: /^(Надіслати прохання|Призначити|Застосувати)$/ });
    await expect(action).toBeVisible();
    await shot(page, `${device}-${theme}-2-selected`);

    const more = region.getByRole('button', { name: /^Ще варіанти \(\d+\)$/ });
    if (await more.isVisible()) {
      await more.click();
      await expect(region.getByRole('button', { name: 'Менше варіантів' })).toHaveAttribute('aria-expanded', 'true');
      await shot(page, `${device}-${theme}-3-more`);
    }

    // ручний виняток: діалог зі станами викладачів і приміткою
    await region.getByRole('button', { name: 'Інший викладач…' }).click();
    const manual = page.getByRole('dialog', { name: /^Інший викладач на \d пару$/ });
    await expect(manual.getByText('Ручне призначення — лише як виняток.')).toBeVisible();
    await expect(manual.getByRole('radio').first()).toBeVisible();
    await shot(page, `${device}-${theme}-4-other-teacher`);
    await manual.getByRole('button', { name: 'Закрити' }).first().click();
    await expect(manual).toBeHidden();

    // план дня: пропозиція, підсумок і кнопка з кількістю
    await region.getByRole('button', { name: 'Запропонувати план дня' }).click();
    const plan = page.getByRole('dialog', { name: /^План дня:/ });
    const apply = plan.getByRole('button', { name: /^(Надіслати всі|Призначити всі) \(\d+\)$/ });
    await expect(apply).toBeVisible({ timeout: 15_000 });
    await expect(plan.getByRole('combobox')).toHaveCount(2);
    await shot(page, `${device}-${theme}-5-plan`);
  }

  // пакет рішень: одна дія — обидві пари вирішено, тост пропонує скасувати
  const plan = page.getByRole('dialog', { name: /^План дня:/ });
  await plan.getByRole('button', { name: /^(Надіслати всі|Призначити всі) \(2\)$/ }).click();
  await expect(plan).toBeHidden();
  await expect(page.getByText(/^План застосовано: 2\s+рішення/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Скасувати' }).last()).toBeVisible();
  await shot(page, `${device}-dark-6-plan-applied`);
  const decided = await page.evaluate(async (date) => {
    const changes = (await (await fetch(`/api/admin/changes?date=${date}`)).json()) as Array<{ createdAt: string; status: string }>;
    return changes.filter((c) => c.status !== 'CANCELLED' && c.createdAt >= '2026-11-09T05:40:00').length;
  }, DAY);
  expect(decided).toBe(2);
});
