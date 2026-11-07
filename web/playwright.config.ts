import { defineConfig, devices } from '@playwright/test';

// Можна запустити на вже встановленому Chromium, не завантажуючи новий: PW_CHROMIUM_PATH=…/chrome.exe
const executablePath = process.env.PW_CHROMIUM_PATH || undefined;

/**
 * E2E-тести SPA. Запуск: `npm run e2e` (піднімає dev-сервер на 5180, якщо він ще не працює).
 * Бекенд для цих тестів не потрібен: перевіряються оболонка, маршрути, тема й компоненти.
 * Браузер встановлюється окремо: `npx playwright install chromium`.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:5180',
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
    command: 'npm run dev',
    url: 'http://localhost:5180',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
