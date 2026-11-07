/// <reference types="vitest/config" />
import { rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type Plugin } from 'vite';

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
    plugins: [react(), tailwindcss(), dropMockServiceWorker()],
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
