import { useSyncExternalStore } from 'react';

function subscribe(onChange: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  window.addEventListener('scroll', onChange, { passive: true });
  return () => window.removeEventListener('scroll', onChange);
}

/** Чи прокручено сторінку хоч трохи: шапка показує лінію знизу лише тоді, коли під нею є вміст. */
export function useScrolled(threshold = 4): boolean {
  return useSyncExternalStore(
    subscribe,
    () => typeof window !== 'undefined' && window.scrollY > threshold,
    () => false,
  );
}
