import { expect, test } from '@playwright/test';
import { mockId } from '../src/mocks/ids';
import { loginWithMockRoles } from './support/mock-auth';

// Порівняння версій на телефоні: кожен рядок — два однакові рядки тексту, без переносу посеред «·».
const PUBLISHED = mockId('timetable-version', 'excel-2025-26-semester-1');
const DEMO_DRAFT = mockId('timetable-version', 'demo-draft-4ki1-even-days');

test('рядок змін на телефоні: «вид · хто» одним рядком, «було → стало» і кнопка — другим', async ({ page, isMobile }) => {
  test.skip(!isMobile, 'Розкладка для вузького екрана');
  await loginWithMockRoles(page, 'ADMIN');
  await page.goto(`/admin/timetable?version=${DEMO_DRAFT}&mode=diff&base=${PUBLISHED}`);
  const row = page.locator('[data-diff-row]').first();
  await expect(row).toBeVisible({ timeout: 30_000 });

  const first = await row.locator('[data-diff-line="1"]').boundingBox();
  const second = await row.locator('[data-diff-line="2"]').boundingBox();
  const button = await row.getByRole('button', { name: 'Показати в сітці' }).boundingBox();
  if (!first || !second || !button) throw new Error('Рядок змін не видно');
  // кожен рівень — один рядок тексту (обрізаний, а не перенесений)
  expect(first.height).toBeLessThan(30);
  expect(second.height).toBeLessThan(30);
  expect(second.y).toBeGreaterThanOrEqual(first.y + first.height - 1);
  // кнопка праворуч від обох рівнів, а не окремим третім рядком
  expect(button.x).toBeGreaterThan(first.x + first.width - 1);
  expect(button.y).toBeLessThan(second.y + second.height);
});
