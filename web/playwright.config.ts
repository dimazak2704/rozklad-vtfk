import { defineConfig, devices } from '@playwright/test';

// Можна запустити на вже встановленому Chromium, не завантажуючи новий: PW_CHROMIUM_PATH=…/chrome.exe
const executablePath = process.env.PW_CHROMIUM_PATH || undefined;
// Порт dev-сервера можна змінити (PW_PORT), щоб кілька копій проєкту тестувались одночасно
const port = Number(process.env.PW_PORT || 5180);
const baseURL = `http://localhost:${port}`;

/**
 * E2E-тести SPA. Запуск: `npm run e2e` (піднімає dev-сервер на 5180, якщо він ще не працює).
 * Бекенд для цих тестів не потрібен: перевіряються оболонка, маршрути, тема й компоненти.
 * Браузер встановлюється окремо: `npx playwright install chromium`.
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
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
