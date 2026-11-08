/**
 * Клавіатура в сітці: стрілки ведуть до найближчої клітинки в напрямку натискання.
 * Та сама геометрія працює і для переходу фокусом між парами, і для перенесення пари з клавіатури
 * (сенсор dnd-kit: Пробіл/Enter — підхопити, стрілки — до сусіднього слота, Пробіл/Enter — поставити).
 */
import { KeyboardCode, type KeyboardCoordinateGetter } from '@dnd-kit/core';

export type Direction = 'left' | 'right' | 'up' | 'down';

export interface RectLike {
  left: number;
  top: number;
  width: number;
  height: number;
}

const ARROWS: Record<string, Direction> = {
  ArrowLeft: 'left',
  ArrowRight: 'right',
  ArrowUp: 'up',
  ArrowDown: 'down',
};

export function arrowDirection(key: string): Direction | null {
  return ARROWS[key] ?? null;
}

const center = (rect: RectLike) => ({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });

/**
 * Найближчий прямокутник у напрямку: основна вісь — відстань уперед, поперечна — штраф утричі,
 * щоб «праворуч» означало «в тому ж рядку праворуч», а не «будь-де праворуч і нижче».
 */
export function nearestInDirection<T extends { rect: RectLike }>(from: RectLike, candidates: readonly T[], direction: Direction): T | null {
  const origin = center(from);
  let best: T | null = null;
  let bestScore = Number.POSITIVE_INFINITY;
  for (const candidate of candidates) {
    const point = center(candidate.rect);
    const dx = point.x - origin.x;
    const dy = point.y - origin.y;
    const forward = direction === 'right' ? dx : direction === 'left' ? -dx : direction === 'down' ? dy : -dy;
    const sideways = direction === 'left' || direction === 'right' ? Math.abs(dy) : Math.abs(dx);
    // лише те, що справді попереду (з допуском на однакові межі сусідніх клітинок)
    if (forward <= 1) continue;
    const score = forward + sideways * 3;
    if (score < bestScore) {
      best = candidate;
      bestScore = score;
    }
  }
  return best;
}

const KEY_TO_DIRECTION: Partial<Record<string, Direction>> = {
  [KeyboardCode.Left]: 'left',
  [KeyboardCode.Right]: 'right',
  [KeyboardCode.Up]: 'up',
  [KeyboardCode.Down]: 'down',
};

/**
 * Стрілки під час перенесення з клавіатури: перескакуємо на центр сусідньої доступної цілі
 * (вимкнені цілі dnd-kit не віддає), а не зсуваємось на 25 пікселів, як за замовчуванням.
 */
export const gridKeyboardCoordinates: KeyboardCoordinateGetter = (event, { context }) => {
  const direction = KEY_TO_DIRECTION[event.code];
  if (!direction) return undefined;
  event.preventDefault();
  const { collisionRect, droppableRects, droppableContainers, over } = context;
  if (!collisionRect) return undefined;
  const from = over ? (droppableRects.get(over.id) ?? collisionRect) : collisionRect;
  const candidates = droppableContainers
    .getEnabled()
    .flatMap((container) => {
      const rect = droppableRects.get(container.id);
      return rect && container.id !== over?.id ? [{ id: container.id, rect }] : [];
    });
  const next = nearestInDirection(from, candidates, direction);
  if (!next) return undefined;
  return {
    x: next.rect.left + next.rect.width / 2 - collisionRect.width / 2,
    y: next.rect.top + next.rect.height / 2 - collisionRect.height / 2,
  };
};

/** Перехід фокусом між елементами сітки (`[data-roving]`) стрілками. Повертає, чи оброблено клавішу. */
export function moveRovingFocus(container: HTMLElement, current: HTMLElement, key: string): boolean {
  const direction = arrowDirection(key);
  if (!direction) return false;
  const items = [...container.querySelectorAll<HTMLElement>('[data-roving]')].filter((item) => item !== current);
  const next = nearestInDirection(
    current.getBoundingClientRect(),
    items.map((element) => ({ element, rect: element.getBoundingClientRect() })),
    direction,
  );
  if (!next) return false;
  next.element.focus();
  return true;
}
