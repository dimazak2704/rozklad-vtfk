import { defineConfig, devices } from '@playwright/test';

/**
 * E2E на справжньому стенді: SPA → gateway :8080 → core/importer/notification, вхід через Keycloak :8180.
 * Не входить у `npm run e2e` (там мок-API). Запуск: `npm run e2e:real` при піднятому стенді з даними
 * (`scripts/dev-stack.sh up --no-telegram && scripts/dev-stack.sh seed`, docs/RUNNING.md).
 *
 * SPA береться з dev-сервера Vite на 5180 (єдиний dev-порт, дозволений у клієнті rozklad-web Keycloak)
 * без мок-API, або з nginx-контейнера: REAL_BASE_URL=http://localhost:8088 (`dev-stack.sh up --web`).
 * Сценарій заміни змінює дані стенду (відсутність, заміна, розпорядження на вільну дату попереду).
 */
const executablePath = process.env.PW_CHROMIUM_PATH || undefined;
const external = process.env.REAL_BASE_URL;
const baseURL = external || 'http://localhost:5180';

export default defineConfig({
  testDir: './e2e-real',
  // один стенд і спільні дані: тести йдуть по черзі
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: 'list',
  globalSetup: './e2e-real/support/global-setup.ts',
  use: {
    baseURL,
    locale: 'uk-UA',
    timezoneId: 'Europe/Kyiv',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: executablePath ? { executablePath } : undefined,
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    {
      name: 'tablet',
      testMatch: /\.(public|resilience)\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 768, height: 1024 }, hasTouch: true },
    },
    {
      name: 'mobile',
      testMatch: /\.(public|resilience)\.spec\.ts$/,
      use: { ...devices['Pixel 7'], viewport: { width: 375, height: 812 } },
    },
  ],
  webServer: external
    ? undefined
    : {
        command: 'npx vite --port 5180 --strictPort',
        url: baseURL,
        reuseExistingServer: process.env.REAL_REUSE_SERVER === '1',
        timeout: 60_000,
        // справжній бекенд і справжній вхід: жодних моків і фіктивних ролей з .env.local
        env: { VITE_USE_MOCKS: '', VITE_DEV_AUTH_ROLES: '' },
      },
});
