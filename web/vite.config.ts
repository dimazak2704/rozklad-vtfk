/// <reference types="vitest/config" />
import { rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';
import { offlineShellPlugin } from './src/api/offline-shell-plugin.ts';
import { isCachedPageNavigation } from './src/api/page-cache-route.ts';
import { isCachedPublicApiRequest, PUBLIC_API_CACHE } from './src/api/public-api-cache.ts';
import { savedCopyPlugin } from './src/api/saved-copy-plugin.ts';

const DAY_SECONDS = 24 * 60 * 60;
const rootDir = fileURLToPath(new URL('.', import.meta.url));
/** Вхід збірки для стилів сторінки входу auth (/brand/auth.css). */
const AUTH_CSS_ENTRY = 'brand-auth';

/**
 * PWA для публічних сторінок (UI.md §4.13): встановлюється на телефон, оболонка кешується заздалегідь,
 * а частини коду й публічні відповіді API — під час перегляду. Розклад береться з мережі (NetworkFirst),
 * без неї — з кешу: останні переглянуті тижні відкриваються офлайн, а копію з кешу позначає savedCopyPlugin.
 * Service Worker лише в production-збірці:
 * у dev працює Service Worker мок-API (MSW), і два воркери на одному scope заважали б один одному.
 */
function pwa(): Plugin[] {
  return VitePWA({
    registerType: 'autoUpdate',
    // окремий скрипт, а не вбудований: CSP у nginx дозволяє лише скрипти з того ж походження
    injectRegister: 'script-defer',
    devOptions: { enabled: false },
    includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
    manifest: {
      name: 'Розклад ВТФК',
      short_name: 'Розклад',
      description: 'Розклад занять і заміни Вінницького технічного фахового коледжу: групи, викладачі, аудиторії, дзвінки.',
      lang: 'uk',
      dir: 'ltr',
      id: '/',
      start_url: '/',
      scope: '/',
      display: 'standalone',
      orientation: 'any',
      background_color: '#f3f6fa',
      theme_color: '#0c3669',
      categories: ['education'],
      icons: [
        { src: '/pwa-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
        { src: '/pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
        { src: '/pwa-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
      ],
      shortcuts: [
        { name: 'Заміни', short_name: 'Заміни', url: '/changes', icons: [{ src: '/pwa-192.png', sizes: '192x192' }] },
        { name: 'Дзвінки', short_name: 'Дзвінки', url: '/bells', icons: [{ src: '/pwa-192.png', sizes: '192x192' }] },
      ],
    },
    workbox: {
      // заздалегідь — лише код оболонки; HTML не тут: збережений index.html після оновлення сайту посилався б
      // на частини коду, яких на сервері вже немає. Сторінки — NetworkFirst нижче (rozklad-pages).
      // Поліфіл modulepreload — окремий шматок, бо його ділять index.html і login/silent.html
      globPatterns: ['assets/index-*.{js,css}', 'assets/modulepreload-polyfill-*.js', 'favicon.svg'],
      // без navigateFallback: збережений index.html після оновлення сайту посилався б на частини коду,
      // яких на сервері вже немає (порожня сторінка). Переходи — NetworkFirst нижче, оболонка лише офлайн.
      navigateFallback: undefined,
      cleanupOutdatedCaches: true,
      // registerSW.js (script-defer) лише реєструє воркер і не шле SKIP_WAITING: без цього новий воркер
      // чекав би, доки закриються всі вкладки й PWA, а старий і далі віддавав би стару оболонку
      skipWaiting: true,
      clientsClaim: true,
      runtimeCaching: [
        {
          // /api, /auth, повернення після входу й сторінки з одноразовим токеном завжди йдуть у мережу
          urlPattern: isCachedPageNavigation,
          handler: 'NetworkFirst',
          options: {
            cacheName: 'rozklad-pages',
            networkTimeoutSeconds: 4,
            expiration: { maxEntries: 40, maxAgeSeconds: 21 * DAY_SECONDS },
            cacheableResponse: { statuses: [200] },
            plugins: [offlineShellPlugin],
          },
        },
        {
          // файли з хешем у назві не змінюються
          urlPattern: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith('/assets/'),
          handler: 'CacheFirst',
          options: { cacheName: 'rozklad-assets', expiration: { maxEntries: 250, maxAgeSeconds: 60 * DAY_SECONDS } },
        },
        {
          // запит з токеном (персонал: архів розкладу й замін) маршрут не бере — він іде в мережу й не зберігається,
          // інакше токен лежав би в Cache Storage після виходу
          urlPattern: isCachedPublicApiRequest,
          handler: 'NetworkFirst',
          options: {
            cacheName: PUBLIC_API_CACHE,
            networkTimeoutSeconds: 5,
            expiration: { maxEntries: 120, maxAgeSeconds: 21 * DAY_SECONDS },
            cacheableResponse: { statuses: [200] },
            // копія з кешу (сервер мовчить понад 5 с чи недоступний) отримує позначку з датою:
            // розклад, «Заміни» й головна кажуть «показано збережене», навіть коли браузер вважає, що мережа є
            plugins: [savedCopyPlugin],
          },
        },
      ],
    },
  });
}

/**
 * Service Worker мок-API (public/mockServiceWorker.js) потрібен лише в dev: прибираємо його з production-збірки,
 * щоб у nginx-образ і кеш PWA не потрапив файл, який там ніхто не реєструє.
 */
function dropMockServiceWorker(): Plugin {
  let outDir = 'dist';
  return {
    name: 'rozklad:drop-mock-service-worker',
    apply: 'build',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir);
    },
    // до інших closeBundle (vite-plugin-pwa збирає список кешу саме там), щоб файл туди не потрапив
    closeBundle: {
      order: 'pre',
      sequential: true,
      async handler() {
        await rm(resolve(outDir, 'mockServiceWorker.js'), { force: true });
      },
    },
  };
}

// Dev-сервер на 5180 (5173 зайнятий іншим проєктом), /api проксіюємо на gateway.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080';

  const proxy = {
    '/api': {
      target: apiTarget,
      changeOrigin: true,
      // SSE і можливі WebSocket-и мають іти без буферизації
      ws: true,
    },
  };

  return {
    plugins: [react(), tailwindcss(), dropMockServiceWorker(), ...pwa()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5180,
      strictPort: true,
      proxy,
    },
    preview: {
      port: 5181,
      strictPort: true,
      proxy,
    },
    build: {
      target: 'es2022',
      // карти коду генеруються для налагодження, але не підключаються в бандл
      sourcemap: 'hidden',
      rolldownOptions: {
        // стилі сторінки входу сервісу auth: окремий вхід із фіксованою назвою dist/brand/auth.css;
        // login/silent.html — сторінка тихого оновлення входу в прихованому iframe (dist/login/silent.html)
        input: {
          index: resolve(rootDir, 'index.html'),
          silent: resolve(rootDir, 'login/silent.html'),
          [AUTH_CSS_ENTRY]: resolve(rootDir, 'src/styles/auth-page.css'),
        },
        output: {
          assetFileNames: (asset) =>
            asset.names.includes(`${AUTH_CSS_ENTRY}.css`) ? 'brand/auth.css' : 'assets/[name]-[hash][extname]',
        },
      },
    },
    test: {
      environment: 'jsdom',
      // кілька агентів збирають паралельно: обмежуємо кількість воркерів, щоб не забрати всю пам'ять
      maxWorkers: 3,
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.mjs'],
      css: false,
      restoreMocks: true,
      unstubGlobals: true,
      unstubEnvs: true,
      // npm run test:coverage → coverage/lcov.info (SonarCloud у CI) і підсумок у консолі.
      // Лічимо лише код застосунку: без тестів, мок-API, тестових утиліт і dev-сторінки kitchen-sink
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/mocks/**', 'src/pages/dev/**', 'src/**/*.d.ts'],
        reporter: ['text-summary', 'lcov'],
        reportsDirectory: 'coverage',
      },
    },
  };
});
