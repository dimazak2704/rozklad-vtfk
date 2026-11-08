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

/** Скільки чекати, чи не зникне відкривач після дії (мутація, оновлення списку). */
export const FOCUS_RESCUE_MS = 10_000;

const CONTAINER = 'section, article, li, main, [role="region"], [role="listitem"], [role="group"], [aria-label], [aria-labelledby]';

/** Батьки елемента від найближчого — щоб знайти, що лишилося на сторінці, коли він сам зник. */
function ancestorsOf(element: HTMLElement): HTMLElement[] {
  const chain: HTMLElement[] = [];
  for (let node = element.parentElement; node && node !== document.body; node = node.parentElement) chain.push(node);
  return chain;
}

/** Найближчий збережений контейнер відкривача (оновлена картка, список, розділ), інакше `<main>`. */
function survivingContainer(chain: readonly HTMLElement[]): HTMLElement | null {
  const connected = chain.filter((node) => node.isConnected);
  return connected.find((node) => node.matches(CONTAINER)) ?? connected[0] ?? document.querySelector('main');
}

function focusContainer(container: HTMLElement): void {
  if (container.tabIndex < 0 && !container.hasAttribute('tabindex')) container.setAttribute('tabindex', '-1');
  container.focus({ preventScroll: true });
}

/**
 * Після підтвердження дія часто прибирає саме ту кнопку, з якої відкрили вікно («Скасувати рішення»,
 * «Видалити відсутність»): фокус падав на `<body>`, і користувач клавіатури починав сторінку спочатку
 * (WCAG 2.4.3). Поки фокус лишається на відкривачі (до {@link FOCUS_RESCUE_MS}), стежимо: якщо його
 * прибрали, фокус переходить на найближчий контейнер, що лишився.
 */
export function keepFocusNear(opener: HTMLElement, chain: readonly HTMLElement[] = ancestorsOf(opener)): void {
  if (typeof MutationObserver === 'undefined') return;
  let stopped = false;
  const rescue = (): boolean => {
    if (opener.isConnected) return false;
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== document.body && active.isConnected) return true;
    const container = survivingContainer(chain);
    if (container) focusContainer(container);
    return true;
  };
  const observer = new MutationObserver(() => {
    if (rescue()) stop();
  });
  const onFocusIn = (event: FocusEvent) => {
    if (event.target !== opener) stop();
  };
  const timer = setTimeout(() => stop(), FOCUS_RESCUE_MS);
  function stop() {
    if (stopped) return;
    stopped = true;
    observer.disconnect();
    document.removeEventListener('focusin', onFocusIn, true);
    clearTimeout(timer);
  }
  if (rescue()) {
    stop();
    return;
  }
  observer.observe(document.body, { childList: true, subtree: true });
  document.addEventListener('focusin', onFocusIn, true);
}

/**
 * Обробники `onOpenAutoFocus` / `onCloseAutoFocus` для `Content` діалогів Radix. Власні обробники
 * викликаються першими; якщо вони скасували типову поведінку закриття, фокус не чіпаємо. Якщо
 * відкривача вже немає (або дія його прибере), фокус іде на найближчий контейнер, що лишився.
 */
export function useReturnFocus(
  onOpenAutoFocus?: (event: Event) => void,
  onCloseAutoFocus?: (event: Event) => void,
): { onOpenAutoFocus: (event: Event) => void; onCloseAutoFocus: (event: Event) => void } {
  const opener = useRef<{ element: HTMLElement; chain: HTMLElement[] } | null>(null);
  return {
    onOpenAutoFocus: (event) => {
      const origin = focusOrigin();
      opener.current = origin ? { element: origin, chain: ancestorsOf(origin) } : null;
      onOpenAutoFocus?.(event);
    },
    onCloseAutoFocus: (event) => {
      onCloseAutoFocus?.(event);
      if (event.defaultPrevented) return;
      const target = opener.current;
      if (!target) return;
      event.preventDefault();
      if (!target.element.isConnected) {
        const container = survivingContainer(target.chain);
        if (container) focusContainer(container);
        return;
      }
      target.element.focus();
      keepFocusNear(target.element, target.chain);
    },
  };
}
