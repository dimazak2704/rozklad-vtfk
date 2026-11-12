import { useSyncExternalStore } from 'react';

/** Телефон: вузький екран або грубий вказівник без наведення. Тут картка деталей — шторка знизу. */
const QUERY = '(max-width: 639.98px), (hover: none) and (pointer: coarse)';

/** Один MediaQueryList на всі клітинки: знімок читається на кожному рендері, тож не створюємо його щоразу. */
let shared: MediaQueryList | null = null;
function list(): MediaQueryList | null {
  if (typeof window === 'undefined' || !window.matchMedia) return null;
  shared ??= window.matchMedia(QUERY);
  return shared;
}

function subscribe(onChange: () => void): () => void {
  const media = list();
  media?.addEventListener?.('change', onChange);
  return () => media?.removeEventListener?.('change', onChange);
}

function snapshot(): boolean {
  return list()?.matches ?? false;
}

export function usePhoneLayout(): boolean {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}
