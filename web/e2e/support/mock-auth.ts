import type { Page } from '@playwright/test';

/** Той самий ключ, що MOCK_ROLES_STORAGE_KEY у src/auth/roles.ts. */
const MOCK_ROLES_STORAGE_KEY = 'rozklad.mockRoles';

/**
 * «Входить» фіктивним користувачем у мок-режимі (e2e-сервер стартує з VITE_USE_MOCKS=1): ролі пишуться
 * в sessionStorage до запуску застосунку на кожній навігації. Викликайте до першого `page.goto`.
 */
export async function loginWithMockRoles(page: Page, ...roles: Array<'ADMIN' | 'TEACHER'>): Promise<void> {
  await page.addInitScript(
    ([key, value]) => window.sessionStorage.setItem(key, value),
    [MOCK_ROLES_STORAGE_KEY, roles.join(',')] as const,
  );
}
