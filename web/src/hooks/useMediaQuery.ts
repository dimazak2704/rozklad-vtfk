import { useCallback, useSyncExternalStore } from 'react';

/**
 * Чи збігається медіазапит зараз; оновлюється при зміні розміру вікна.
 * `serverValue` — значення, коли `matchMedia` немає (тести без вікна, SSR).
 */
export function useMediaQuery(query: string, serverValue = false): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (typeof window === 'undefined' || !window.matchMedia) return () => {};
      const list = window.matchMedia(query);
      list.addEventListener?.('change', onChange);
      return () => list.removeEventListener?.('change', onChange);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => (typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(query).matches : serverValue),
    () => serverValue,
  );
}
