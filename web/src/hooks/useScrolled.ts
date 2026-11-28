import { useEffect, useState } from 'react';

/**
 * Чи прокручено сторінку хоч трохи: шапка показує лінію знизу лише тоді, коли під нею є вміст.
 *
 * `window.scrollY` не читаємо під час рендеру й коміту: на щойно вставленій сторінці це змушує браузер
 * перерахувати розкладку всієї сторінки посеред задачі рендеру (на першому завантаженні — десятки мілісекунд).
 * Перша перевірка — перед найближчим кадром (сторінку могли відкрити вже прокрученою), далі — подія scroll.
 */
export function useScrolled(threshold = 4): boolean {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const update = () => setScrolled(window.scrollY > threshold);
    const frame = window.requestAnimationFrame(update);
    window.addEventListener('scroll', update, { passive: true });
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener('scroll', update);
    };
  }, [threshold]);
  return scrolled;
}
