import { expect, test, type Page } from '@playwright/test';

// Dev-сервер компілює модулі на льоту: перший запуск сторінки буває повільним
test.describe.configure({ timeout: 90_000 });

/** Понеділок 09.11.2026, 10:05 у Києві: іде друга пара. */
const NOW = new Date('2026-11-09T08:05:00Z');

async function open(page: Page, path: string): Promise<void> {
  await page.clock.setFixedTime(NOW);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 30_000 });
}

/** Анімації Web Animations API (не CSS-переходи) з їхніми ключовими кадрами transform. */
function scriptedAnimations(page: Page): Promise<string[][]> {
  return page.evaluate(() =>
    document
      .getAnimations()
      .filter((animation) => animation.constructor.name === 'Animation')
      .map((animation) => (animation.effect as KeyframeEffect).getKeyframes().map((frame) => String(frame.transform ?? frame.opacity))),
  );
}

async function nextWeekAnimations(page: Page): Promise<string[][]> {
  await page.getByRole('button', { name: /Наступний тиждень/ }).click();
  // вхід триває 200 мс: збираємо все, що встигло запуститися за цей час
  const seen: string[][] = [];
  for (let i = 0; i < 10; i += 1) {
    seen.push(...(await scriptedAnimations(page)));
    await page.waitForTimeout(25);
  }
  return seen;
}

test.describe('рух і деталі при наведенні', () => {
  test('наступний тиждень входить справа на 8 px', async ({ page }) => {
    await open(page, '/g/4%D0%9A%D0%861?view=week');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/4КІ1/);
    await page.waitForLoadState('networkidle');
    const seen = await nextWeekAnimations(page);
    expect(seen).toContainEqual(['translateX(8px)', 'none']);
  });

  test('без вподобання руху нічого не ковзає', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await open(page, '/g/4%D0%9A%D0%861?view=week');
    await page.waitForLoadState('networkidle');
    const seen = await nextWeekAnimations(page);
    expect(seen).toEqual([]);
  });

  test('мишею: смуга доріжки дня називає пару й час, код групи — групу повністю', async ({ page, isMobile }) => {
    test.skip(isMobile, 'на дотику підказок немає: ті самі відомості — у дзвінках і на сторінці групи');
    await open(page, '/');
    const segment = page.locator('[data-track-segment]').nth(1);
    await segment.hover();
    await expect(page.getByRole('tooltip')).toHaveText(/^2 пара · 09:40–10:40 · іде зараз$/);

    await page.mouse.move(0, 0);
    const course = page.getByRole('button', { name: /^4 курс/ });
    if ((await course.getAttribute('aria-expanded')) !== 'true') await course.click();
    await page.getByRole('link', { name: '4КІ1', exact: true }).hover();
    await expect(page.getByRole('tooltip')).toContainText('4 курс');
  });
});
