/// <reference types="vitest/config" />
import { rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

const DAY_SECONDS = 24 * 60 * 60;

/**
 * PWA для публічних сторінок (UI.md §4.13): встановлюється на телефон, оболонка кешується заздалегідь,
 * а частини коду й публічні відповіді API — під час перегляду. Розклад береться з мережі (NetworkFirst),
 * без неї — з кешу: останні переглянуті тижні відкриваються офлайн. Service Worker лише в production-збірці:
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
      // заздалегідь — лише оболонка; сторінки адмінки й кабінету не потрапляють у кеш, поки їх не відкрили
      globPatterns: ['index.html', 'assets/index-*.{js,css}', 'favicon.svg'],
      navigateFallback: 'index.html',
      // /api (ICS, файли розпоряджень) і повернення з Keycloak завжди йдуть у мережу
      navigateFallbackDenylist: [/^\/api\//, /^\/auth\//],
      cleanupOutdatedCaches: true,
      runtimeCaching: [
        {
          // файли з хешем у назві не змінюються
          urlPattern: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith('/assets/'),
          handler: 'CacheFirst',
          options: { cacheName: 'rozklad-assets', expiration: { maxEntries: 250, maxAgeSeconds: 60 * DAY_SECONDS } },
        },
        {
          urlPattern: ({ url, sameOrigin }) =>
            sameOrigin && /^\/api\/public\/(meta|groups|teachers|rooms|bells|schedule|changes)(\/|$)/.test(url.pathname),
          handler: 'NetworkFirst',
          options: {
            cacheName: 'rozklad-public-api',
            networkTimeoutSeconds: 5,
            expiration: { maxEntries: 120, maxAgeSeconds: 21 * DAY_SECONDS },
            cacheableResponse: { statuses: [200] },
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
    },
  };
});
