import { useRef } from 'react';

/**
 * Повернення фокуса після закриття модального вікна (WCAG 2.4.3).
 *
 * Radix повертає фокус на свій `Trigger`, але вікна застосунку відкриваються зі стану
 * (`open={…}` або умовний рендер) без `Trigger`, тож після закриття фокус падав на `<body>`, і
 * наступний Tab починав сторінку спочатку. Тут запамʼятовуємо елемент, з якого вікно відкрили, і
 * повертаємо фокус на нього.
 */

// останній сфокусований елемент поза меню: пункт меню зникає раніше, ніж відкривається вікно
let lastFocused: HTMLElement | null = null;

if (typeof document !== 'undefined') {
  document.addEventListener(
    'focusin',
    (event) => {
      const target = event.target;
      if (target instanceof HTMLElement && !target.closest('[role="menu"]')) lastFocused = target;
    },
    true,
  );
}

/** Елемент, на який треба повернути фокус: кнопка-відкривач, а для пункту меню — кнопка цього меню. */
export function focusOrigin(): HTMLElement | null {
  if (typeof document === 'undefined') return null;
  const active = document.activeElement;
  if (active instanceof HTMLElement && active !== document.body) {
    const menu = active.closest('[role="menu"]');
    if (!menu) return active;
    const triggerId = menu.getAttribute('aria-labelledby');
    const trigger = triggerId ? document.getElementById(triggerId) : null;
    if (trigger) return trigger;
  }
  return lastFocused?.isConnected ? lastFocused : null;
}

/**
 * Обробники `onOpenAutoFocus` / `onCloseAutoFocus` для `Content` діалогів Radix. Власні обробники
 * викликаються першими; якщо вони скасували типову поведінку закриття, фокус не чіпаємо.
 */
export function useReturnFocus(
  onOpenAutoFocus?: (event: Event) => void,
  onCloseAutoFocus?: (event: Event) => void,
): { onOpenAutoFocus: (event: Event) => void; onCloseAutoFocus: (event: Event) => void } {
  const opener = useRef<HTMLElement | null>(null);
  return {
    onOpenAutoFocus: (event) => {
      opener.current = focusOrigin();
      onOpenAutoFocus?.(event);
    },
    onCloseAutoFocus: (event) => {
      onCloseAutoFocus?.(event);
      if (event.defaultPrevented) return;
      const target = opener.current;
      if (target?.isConnected) {
        event.preventDefault();
        target.focus();
      }
    },
  };
}
