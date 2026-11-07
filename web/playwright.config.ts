import { defineConfig, devices } from '@playwright/test';

// Можна запустити на вже встановленому Chromium, не завантажуючи новий: PW_CHROMIUM_PATH=…/chrome.exe
const executablePath = process.env.PW_CHROMIUM_PATH || undefined;
// Порт dev-сервера можна змінити (PW_PORT), щоб кілька копій проєкту тестувались одночасно
const port = Number(process.env.PW_PORT || 5180);
const baseURL = `http://localhost:${port}`;

/**
 * E2E-тести SPA. Запуск: `npm run e2e` (піднімає власний dev-сервер на PW_PORT, типово 5180).
 * Бекенд не потрібен: сервер стартує з мок-API (VITE_USE_MOCKS=1, src/mocks), користувач — гість.
 * Ролі в тесті: e2e/support/mock-auth.ts. Браузер встановлюється окремо: `npx playwright install chromium`.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  workers: process.env.PW_WORKERS ? Number(process.env.PW_WORKERS) : 2,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL,
    locale: 'uk-UA',
    timezoneId: 'Europe/Kyiv',
    trace: 'retain-on-failure',
    launchOptions: executablePath ? { executablePath } : undefined,
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], viewport: { width: 360, height: 780 } } },
  ],
  webServer: {
    command: `npx vite --port ${port} --strictPort`,
    url: baseURL,
    // лише свій сервер з моками: чужий dev-сервер на тому ж порту (без моків чи з ролями) дав би хибні результати
    reuseExistingServer: false,
    timeout: 60_000,
    // змінні процесу важливіші за .env*-файли: локальний VITE_DEV_AUTH_ROLES не зробить тести «залогіненими»
    env: { VITE_USE_MOCKS: '1', VITE_DEV_AUTH_ROLES: '', VITE_MOCK_DELAY: 'real' },
  },
});
