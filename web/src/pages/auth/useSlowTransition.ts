import { useEffect, useState } from 'react';

/** Скільки чекаємо на перехід між сторінками входу, перш ніж запропонувати спробувати ще раз. */
export const SLOW_TRANSITION_MS = 3_000;

/**
 * `true`, коли очікування `key` триває довше за `SLOW_TRANSITION_MS`. Нова спроба (інший `key`) знову
 * дає повний час; поки `active` хибне, таймер не йде.
 */
export function useSlowTransition(active: boolean, key = 0): boolean {
  const [slowKey, setSlowKey] = useState<number | null>(null);
  useEffect(() => {
    if (!active) return;
    const timer = setTimeout(() => setSlowKey(key), SLOW_TRANSITION_MS);
    return () => clearTimeout(timer);
  }, [active, key]);
  return active && slowKey === key;
}
