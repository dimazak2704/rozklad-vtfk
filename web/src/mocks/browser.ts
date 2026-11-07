/**
 * Мок-API у браузері (лише `npm run dev:mock` або VITE_USE_MOCKS=1 у dev): Service Worker MSW
 * відповідає на /api замість бекенду. Запускається з main.tsx до першого рендеру.
 */
import { setupWorker } from 'msw/browser';
import type { ServerEventMap, ServerEventType } from '@/api/types';
import { emitServerEvent } from './events';
import { controlHandler } from './kit';
import { handlers } from './registry';
import { resetMockStores } from './store';

/** Керування мок-API з консолі браузера чи Playwright: `window.__rozkladMocks.emit('change.updated', …)`. */
export interface MockControls {
  emit: <K extends ServerEventType>(type: K, data: ServerEventMap[K]) => void;
  reset: () => void;
}

declare global {
  interface Window {
    __rozkladMocks?: MockControls;
  }
}

// спершу спільна прослойка (затримка, ?mockError), далі обробники областей
export const worker = setupWorker(controlHandler, ...handlers);

export async function startMockWorker(): Promise<void> {
  await worker.start({
    // запити без мок-обробника (шрифти, модулі Vite, ще не змоковані ендпоінти) ідуть як є
    onUnhandledRequest: 'bypass',
    serviceWorker: { url: `${import.meta.env.BASE_URL}mockServiceWorker.js` },
  });
  window.__rozkladMocks = { emit: emitServerEvent, reset: resetMockStores };
}
