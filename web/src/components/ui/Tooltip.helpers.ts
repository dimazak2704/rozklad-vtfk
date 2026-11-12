/** Затримка підказок і карток при наведенні: досить, щоб курсор «пролетів» повз, і не довше. */
export const HOVER_DELAY_MS = 300;

/** Текст справді обрізано (трикрапка чи line-clamp): ширина чи висота вмісту більша за видиму. */
export function isTruncated(el: HTMLElement | null): boolean {
  if (!el) return false;
  return el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
}
