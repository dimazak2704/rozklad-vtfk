import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';

// Файли з `// @vitest-environment node` (скрипти) не мають DOM — заглушки лише для jsdom
const hasDom = typeof window !== 'undefined';

// Мок-API (src/mocks): запит без обробника — помилка тесту, а не тихий похід у мережу
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  resetMockStores();
});
afterAll(() => server.close());

afterEach(() => {
  if (!hasDom) return;
  cleanup();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

if (hasDom) {
  // jsdom не має matchMedia, ResizeObserver, scrollIntoView — Radix і наші хуки їх викликають
  if (!window.matchMedia) {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    });
  }

  class ResizeObserverStub {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  if (!('ResizeObserver' in window)) {
    Object.defineProperty(window, 'ResizeObserver', { writable: true, value: ResizeObserverStub });
  }

  if (!Element.prototype.scrollIntoView) {
    Element.prototype.scrollIntoView = function scrollIntoView() {};
  }
  if (!Element.prototype.hasPointerCapture) {
    Element.prototype.hasPointerCapture = () => false;
    Element.prototype.releasePointerCapture = () => {};
  }
}
