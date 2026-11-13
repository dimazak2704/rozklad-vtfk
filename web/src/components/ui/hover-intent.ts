import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Скільки після закриття картки наступна відкривається одразу (огляд сітки мишею: клітинка за клітинкою).
 * Як `skipDelayDuration` у підказок, трохи довше: між клітинками курсор іде через рамки й відступи.
 */
export const HOVER_WARM_MS = 400;

/** Відкриті зараз картки: на сторінці одна картка за раз, нова закриває попередню. */
const openCards = new Map<symbol, () => void>();
let lastClosedAt = Number.NEGATIVE_INFINITY;

/** Картку відкрито щойно або вона ще відкрита: наступна — без затримки й без анімації появи. */
export function hoverCardsWarm(now = Date.now()): boolean {
  return openCards.size > 0 || now - lastClosedAt < HOVER_WARM_MS;
}

/** Для тестів: забути відкриті картки й час останнього закриття. */
export function resetHoverCards(): void {
  openCards.clear();
  lastClosedAt = Number.NEGATIVE_INFINITY;
}

export interface HoverIntentState {
  open: boolean;
  /** Перемикання з картки на картку: без анімації появи й зникнення, щоб дві картки не накладались. */
  instant: boolean;
  /** Відкрито наведенням миші (а не Enter, тапом чи кліком). */
  byHover: boolean;
}

const CLOSED: HoverIntentState = { open: false, instant: false, byHover: false };

/**
 * Намір наведення для картки деталей (схема «розігріву»): перша картка відкривається після `openDelay`,
 * а поки якась картка відкрита (чи щойно закрилась) — наступна відкривається одразу, попередня зникає
 * без анімації. Закривається через `closeDelay` після того, як курсор пішов.
 */
export function useHoverIntent({ openDelay, closeDelay }: { openDelay: number; closeDelay: number }) {
  const [id] = useState(() => Symbol('hover-card'));
  const [state, setState] = useState<HoverIntentState>(CLOSED);
  const openRef = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clear = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  }, []);

  const close = useCallback(
    (instant = false) => {
      clear();
      if (openCards.delete(id)) lastClosedAt = Date.now();
      if (!openRef.current) return;
      openRef.current = false;
      setState({ ...CLOSED, instant });
    },
    [clear, id],
  );

  const show = useCallback(
    ({ instant = false, byHover = false }: { instant?: boolean; byHover?: boolean } = {}) => {
      clear();
      for (const [other, closeOther] of openCards) if (other !== id) closeOther();
      openCards.set(id, () => close(true));
      openRef.current = true;
      setState({ open: true, instant, byHover });
    },
    [clear, close, id],
  );

  /** Курсор миші зайшов на тригер (чи на саму картку). */
  const enter = useCallback(() => {
    clear();
    if (openRef.current) return;
    if (hoverCardsWarm()) show({ instant: true, byHover: true });
    else timer.current = setTimeout(() => show({ byHover: true }), openDelay);
  }, [clear, openDelay, show]);

  /** Курсор миші пішов. */
  const leave = useCallback(() => {
    clear();
    if (!openRef.current) return;
    timer.current = setTimeout(() => close(), closeDelay);
  }, [clear, close, closeDelay]);

  useEffect(
    () => () => {
      clear();
      if (openCards.delete(id)) lastClosedAt = Date.now();
    },
    [clear, id],
  );

  return { ...state, enter, leave, show, close };
}
