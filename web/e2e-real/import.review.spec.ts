import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import { apiAs, getJson, login } from './support/real';

/**
 * Імпорт програми дисципліни на справжньому стенді (без ШІ): програма без тижневих годин і з групою, якої
 * немає в каталозі, не стає «готовою» — сторінка веде на «Чернетку», де параметри виправляють без
 * повторного завантаження (round 6, r3-ai-imports-02). Застосування не запускаємо: навантаження не змінюється,
 * а наприкінці параметри знову порожні, щоб завдання не можна було випадково застосувати.
 */
test.describe('імпорт: параметри застосування', () => {
  test('програма без годин на тиждень і з невідомою групою виправляється на сторінці', async ({ page }) => {
    const api = await apiAs('ADMIN');
    const file = readFileSync(fileURLToPath(new URL('../../seed/programs/working-program-ndeksm.doc', import.meta.url)));
    const uploaded = await api.post('/api/import/jobs', {
      multipart: {
        kind: 'DISCIPLINE_PROGRAM',
        options: JSON.stringify({ groupCodes: ['4КІ9'] }),
        file: { name: 'working-program-ndeksm.doc', mimeType: 'application/msword', buffer: file },
      },
    });
    expect(uploaded.status()).toBe(202);
    const { id } = (await uploaded.json()) as { id: string };
    await expect
      .poll(async () => (await getJson<{ status: string }>(api, `/api/import/jobs/${id}`)).status, { timeout: 60_000 })
      .toBe('NEEDS_REVIEW');

    try {
      await login(page, 'ADMIN', `/admin/import?job=${id}`);
      await page.goto(`/admin/import?job=${id}`);
      const applyPanel = page.getByRole('region', { name: 'Застосування імпорту' });
      await expect(applyPanel).toContainText('виправте на вкладці «Чернетка»');
      await expect(applyPanel).not.toContainText('вирішіть 0');
      await expect(applyPanel.getByRole('button', { name: 'Застосувати' })).toBeDisabled();
      await applyPanel.getByRole('button', { name: 'До чернетки' }).click();

      const options = page.getByRole('region', { name: 'Параметри застосування' });
      await expect(options).toContainText('У програмі не вказано годин на тиждень');
      await options.getByRole('button', { name: 'Прибрати групу 4КІ9' }).click();
      await options.getByRole('combobox').first().click();
      await page.getByRole('option', { name: /^4КІ1/ }).click();
      await options.getByRole('spinbutton', { name: /Годин на тиждень/ }).fill('2');
      await options.getByRole('button', { name: 'Зберегти параметри' }).click();

      await expect(applyPanel).toContainText('Готово до застосування');
      await expect(applyPanel.getByRole('button', { name: 'Застосувати' })).toBeEnabled();
    } finally {
      const job = await getJson<{ version: number }>(api, `/api/import/jobs/${id}`);
      const parked = await api.put(`/api/import/jobs/${id}/options`, { data: { version: job.version, options: {} } });
      expect(parked.ok(), await parked.text()).toBe(true);
      await api.dispose();
    }
  });
});
