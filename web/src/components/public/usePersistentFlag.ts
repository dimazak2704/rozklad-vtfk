import { useCallback, useState } from 'react';

function read(key: string, fallback: boolean): boolean {
  try {
    const value = localStorage.getItem(key);
    return value === null ? fallback : value === '1';
  } catch {
    return fallback;
  }
}

/**
 * Так/ні, що памʼятається між відвідинами (відкритий список груп тощо). Без localStorage (приватний
 * режим, заборона) — просто стан сторінки.
 */
export function usePersistentFlag(key: string, fallback = false): [boolean, (next: boolean) => void] {
  const [value, setValue] = useState(() => read(key, fallback));
  const update = useCallback(
    (next: boolean) => {
      setValue(next);
      try {
        localStorage.setItem(key, next ? '1' : '0');
      } catch {
        // сховище недоступне — вибір діє до перезавантаження
      }
    },
    [key],
  );
  return [value, update];
}
