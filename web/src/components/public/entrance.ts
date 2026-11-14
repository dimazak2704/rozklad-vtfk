import { useLayoutEffect, useRef, type RefObject } from 'react';

/**
 * Перша поява розкладу чи списку замін (DESIGN §6, «Перша поява»): стовпці тижня чи рядки списку
 * проявляються зліва направо / згори вниз з кроком 40 мс. Один раз на монтування: гортання тижнів,
 * День ↔ Тиждень і живі оновлення цього не повторюють (там свій рух, useNavTransition).
 */
export const ENTRANCE_STEP_MS = 40;
/** Далі кроку немає: усе, що нижче шостого рядка чи правіше шостого дня, з'являється разом з ним. */
export const ENTRANCE_MAX_STEPS = 6;
export const ENTRANCE_MS = 280;
const RISE_PX = 6;
const EASE_OUT = 'cubic-bezier(0.22, 1, 0.36, 1)';

export interface EntranceTarget {
  el: Element;
  step: number;
}

/**
 * Комірки таблиці з номером їхнього стовпця. Комірка з rowSpan (практика на весь день) займає стовпець і в
 * наступних рядках, тож рахуємо за справжньою позицією, а не за порядком комірок у рядку.
 */
export function tableColumnTargets(table: HTMLTableElement): EntranceTarget[] {
  const targets: EntranceTarget[] = [];
  // скільки ще рядків кожен стовпець зайнятий коміркою згори
  const occupied: number[] = [];
  for (const row of Array.from(table.rows)) {
    let col = 0;
    for (const cell of Array.from(row.cells)) {
      while ((occupied[col] ?? 0) > 0) col += 1;
      targets.push({ el: cell, step: Math.min(col, ENTRANCE_MAX_STEPS) });
      for (let c = col; c < col + Math.max(1, cell.colSpan); c += 1) occupied[c] = Math.max(1, cell.rowSpan);
      col += Math.max(1, cell.colSpan);
    }
    for (let c = 0; c < occupied.length; c += 1) if ((occupied[c] ?? 0) > 0) occupied[c] = (occupied[c] ?? 0) - 1;
  }
  return targets;
}

/** Зовнішні пункти списків (без вкладених і без прихованих від читача екрана службових рядків). */
export function listTargets(root: Element): EntranceTarget[] {
  return Array.from(root.querySelectorAll('li'))
    .filter((li) => {
      if (li.closest('[aria-hidden="true"]')) return false;
      const outer = li.parentElement?.closest('li');
      return !outer || !root.contains(outer);
    })
    .map((el, index) => ({ el, step: Math.min(index, ENTRANCE_MAX_STEPS) }));
}

/** Що проявляється: стовпці таблиці тижня, а без таблиці — пункти списку. */
export function entranceTargets(root: Element): EntranceTarget[] {
  const table = root.querySelector('table');
  return table ? tableColumnTargets(table) : listTargets(root);
}

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Проявляє вміст `ref`, коли він уперше готовий (`ready`). Лише opacity і transform (Web Animations API,
 * композиція, без перерахунку макета); fill «backwards» тримає прозорість тільки під час затримки, тож після
 * появи нічого не лишається на елементах. Без вподобання руху — вміст просто є.
 */
export function useEntrance(ref: RefObject<HTMLElement | null>, ready: boolean): void {
  const played = useRef(false);
  useLayoutEffect(() => {
    if (!ready || played.current) return;
    played.current = true;
    const root = ref.current;
    if (!root || typeof root.animate !== 'function' || reducedMotion()) return;
    const keyframes: Keyframe[] = [
      { opacity: 0, transform: `translateY(${RISE_PX}px)` },
      { opacity: 1, transform: 'none' },
    ];
    for (const { el, step } of entranceTargets(root)) {
      el.animate(keyframes, { duration: ENTRANCE_MS, delay: step * ENTRANCE_STEP_MS, easing: EASE_OUT, fill: 'backwards' });
    }
  }, [ready, ref]);
}
