/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

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
    plugins: [react(), tailwindcss()],
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
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.mjs'],
      css: false,
      restoreMocks: true,
      unstubGlobals: true,
      unstubEnvs: true,
    },
  };
});
