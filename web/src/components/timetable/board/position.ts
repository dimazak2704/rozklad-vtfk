import type { DropTarget } from '../model/grid';

export interface CardPosition {
  left: number;
  top: number;
}

export const PREVIEW_CARD_WIDTH = 288;

/** Де показати картку перевірки слота: праворуч від клітинки, а якщо не влазить — ліворуч. */
export function cardPositionFor(target: DropTarget): CardPosition | null {
  const cell = document.querySelector<HTMLElement>(`[data-cell="${target.entityId}|${target.weekday}|${target.pair}"]`);
  if (!cell) return null;
  const rect = cell.getBoundingClientRect();
  const fitsRight = rect.right + 12 + PREVIEW_CARD_WIDTH < window.innerWidth;
  return {
    left: fitsRight ? rect.right + 8 : Math.max(8, rect.left - PREVIEW_CARD_WIDTH - 8),
    top: Math.min(Math.max(8, rect.top), window.innerHeight - 180),
  };
}
