/** Затримка підказок і карток при наведенні: досить, щоб курсор «пролетів» повз, і не довше. */
export const HOVER_DELAY_MS = 300;

/**
 * Затримка великої картки деталей: довша за підказку, щоб повільний огляд рядка (≈ 400 мс на клітинку)
 * не відкривав і не закривав картку на кожній клітинці, накриваючи сусідні.
 */
export const HOVER_CARD_DELAY_MS = 550;
/** Картка закривається трохи згодом: курсор встигає перейти з тригера на саму картку. */
export const HOVER_CARD_CLOSE_DELAY_MS = 100;

/** Текст справді обрізано (трикрапка чи line-clamp): ширина чи висота вмісту більша за видиму. */
export function isTruncated(el: HTMLElement | null): boolean {
  if (!el) return false;
  return el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
}
