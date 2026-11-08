import { useSyncExternalStore } from 'react';

/** Телефон: вузький екран або грубий вказівник без наведення. Тут картка деталей — шторка знизу. */
const QUERY = '(max-width: 639.98px), (hover: none) and (pointer: coarse)';

function subscribe(onChange: () => void): () => void {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const list = window.matchMedia(QUERY);
  list.addEventListener?.('change', onChange);
  return () => list.removeEventListener?.('change', onChange);
}

function snapshot(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia(QUERY).matches;
}

export function usePhoneLayout(): boolean {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}
