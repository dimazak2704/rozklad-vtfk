import { useCallback, useRef } from 'react';

/** Callback ref, який компонент вішає на елемент реєстру. */
export type RegisterRef = (key: string) => (element: HTMLElement | null) => (() => void) | undefined;

export interface ElementRegistry {
  /** Ref для елемента з цим ключем: реєструє його при монтуванні й прибирає при розмонтуванні. */
  register: RegisterRef;
  /** Зареєстрований елемент або null, якщо його зараз немає на екрані. */
  get: (key: string) => HTMLElement | null;
  /**
   * Після наступного кадру (коли React уже відмалював новий вибір) переводить фокус на елемент і
   * докручує список до нього, не стрибаючи сторінкою.
   */
  focusLater: (key: string) => void;
}

/**
 * Реєстр елементів списку за ключем: замість пошуку в DOM за id батьківський компонент тримає refs своїх
 * рядків і сам переводить на них фокус (J/K, 1–9).
 */
export function useElementRegistry(): ElementRegistry {
  const elements = useRef(new Map<string, HTMLElement>());

  const register = useCallback<RegisterRef>(
    (key) => (element) => {
      if (!element) return undefined;
      const map = elements.current;
      map.set(key, element);
      // прибираємо лише свій елемент: новий рядок з тим самим ключем міг уже зареєструватися
      return () => {
        if (map.get(key) === element) map.delete(key);
      };
    },
    [],
  );

  const get = useCallback((key: string) => elements.current.get(key) ?? null, []);

  const focusLater = useCallback((key: string) => {
    requestAnimationFrame(() => {
      const element = elements.current.get(key);
      element?.focus({ preventScroll: true });
      element?.scrollIntoView({ block: 'nearest' });
    });
  }, []);

  return { register, get, focusLater };
}
