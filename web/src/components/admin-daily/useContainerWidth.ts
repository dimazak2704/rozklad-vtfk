import { useLayoutEffect, useState, type RefObject } from 'react';

/**
 * Ширина елемента (не вікна): робочі екрани диспетчера перебудовуються за місцем, яке лишає бічна панель.
 * До першого вимірювання — ширина вікна, щоб не було зайвого перемикання розкладки.
 */
export function useContainerWidth(ref: RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(() => (typeof window === 'undefined' ? 1280 : window.innerWidth));

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const measured = element.getBoundingClientRect().width;
    if (measured > 0) setWidth(measured);
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width;
      if (next) setWidth(next);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);

  return width;
}
