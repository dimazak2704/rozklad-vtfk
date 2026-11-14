import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Скільки після закриття картки наступна відкривається одразу (огляд сітки мишею: клітинка за клітинкою).
 * Як `skipDelayDuration` у підказок, трохи довше: між клітинками курсор іде через рамки й відступи.
 */
export const HOVER_WARM_MS = 400;

/**
 * Скільки курсор має постояти на іншому тригері всередині безпечної зони відкритої картки, щоб той забрав
 * картку собі. Курсор, що лише перетинає сусідню клітинку дорогою до картки, рухається — і нічого не міняє.
 */
export const HOVER_SWITCH_REST_MS = 150;

export interface Point {
  x: number;
  y: number;
}

/** Відкриті зараз картки: на сторінці одна картка за раз, нова закриває попередню. */
const openCards = new Map<symbol, () => void>();
let lastClosedAt = Number.NEGATIVE_INFINITY;

/**
 * Безпечна зона відкритої картки: трикутник від точки, де курсор покинув тригер, до ближчої сторони картки,
 * і сама картка. Поки курсор у ній, картка не закривається, а сусідні тригери її не перехоплюють.
 */
interface SafeZone {
  owner: symbol;
  apex: Point;
  near: [Point, Point];
  rect: { left: number; top: number; right: number; bottom: number };
  /** Курсор вийшов із зони, і ніхто не чекає на картку: закрити її з звичайною затримкою. */
  release: () => void;
}

let zone: SafeZone | null = null;
/** Тригер, на якому курсор зараз стоїть усередині зони: забере картку, якщо постоїть чи вийде з зони. */
let pending: { id: symbol; take: () => void } | null = null;
let restTimer: ReturnType<typeof setTimeout> | null = null;

function clearRest() {
  if (restTimer) clearTimeout(restTimer);
  restTimer = null;
}

function armRest() {
  clearRest();
  restTimer = setTimeout(() => {
    restTimer = null;
    const next = pending;
    pending = null;
    next?.take();
  }, HOVER_SWITCH_REST_MS);
}

function cancelPending(id: symbol) {
  if (pending?.id !== id) return;
  pending = null;
  clearRest();
}

/** Знак векторного добутку: з якого боку прямої ab лежить p. */
function cross(a: Point, b: Point, p: Point): number {
  return (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
}

const EDGE_TOLERANCE = 1;

/** Точка в трикутнику (межа включно). */
export function inTriangle(p: Point, a: Point, b: Point, c: Point): boolean {
  const d1 = cross(a, b, p);
  const d2 = cross(b, c, p);
  const d3 = cross(c, a, p);
  const hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
  const hasPos = d1 > 0 || d2 > 0 || d3 > 0;
  return !(hasNeg && hasPos);
}

/**
 * Трикутник від точки виходу до ближчої до неї сторони прямокутника картки. Вершину трохи відсуваємо назад,
 * щоб перший крок курсора з тригера (на піксель-два вбік) ще лишався в зоні.
 */
export function safeTriangle(exit: Point, rect: SafeZone['rect']): { apex: Point; near: [Point, Point] } {
  let near: [Point, Point];
  let back: Point;
  if (exit.y <= rect.top) {
    near = [{ x: rect.left, y: rect.top }, { x: rect.right, y: rect.top }];
    back = { x: 0, y: -2 };
  } else if (exit.y >= rect.bottom) {
    near = [{ x: rect.left, y: rect.bottom }, { x: rect.right, y: rect.bottom }];
    back = { x: 0, y: 2 };
  } else if (exit.x <= rect.left) {
    near = [{ x: rect.left, y: rect.top }, { x: rect.left, y: rect.bottom }];
    back = { x: -2, y: 0 };
  } else {
    near = [{ x: rect.right, y: rect.top }, { x: rect.right, y: rect.bottom }];
    back = { x: 2, y: 0 };
  }
  return { apex: { x: exit.x + back.x, y: exit.y + back.y }, near };
}

function inZone(p: Point): boolean {
  if (!zone) return false;
  const { rect } = zone;
  if (
    p.x >= rect.left - EDGE_TOLERANCE &&
    p.x <= rect.right + EDGE_TOLERANCE &&
    p.y >= rect.top - EDGE_TOLERANCE &&
    p.y <= rect.bottom + EDGE_TOLERANCE
  ) {
    return true;
  }
  return inTriangle(p, zone.apex, zone.near[0], zone.near[1]);
}

function onZoneMove(event: PointerEvent) {
  if (!zone || event.pointerType !== 'mouse') return;
  if (inZone({ x: event.clientX, y: event.clientY })) {
    // курсор ще в дорозі: сусідній тригер чекає, поки він зупиниться
    if (pending) armRest();
    return;
  }
  const leaving = zone;
  const next = pending;
  endZone();
  // вийшов із зони на інший тригер — той забирає картку одразу; в порожнечу — картка закривається
  if (next) next.take();
  else leaving.release();
}

function startZone(next: SafeZone) {
  endZone();
  zone = next;
  window.addEventListener('pointermove', onZoneMove, { capture: true, passive: true });
}

function endZone() {
  if (!zone) return;
  zone = null;
  pending = null;
  clearRest();
  window.removeEventListener('pointermove', onZoneMove, { capture: true });
}

/** Картку відкрито щойно або вона ще відкрита: наступна — без затримки й без анімації появи. */
export function hoverCardsWarm(now = Date.now()): boolean {
  return openCards.size > 0 || now - lastClosedAt < HOVER_WARM_MS;
}

/** Для тестів: забути відкриті картки, зону й час останнього закриття. */
export function resetHoverCards(): void {
  openCards.clear();
  endZone();
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

export interface HoverIntentOptions {
  openDelay: number;
  closeDelay: number;
  /**
   * Картка, до якої курсор може перейти (у ній є посилання). З нею вмикається безпечна зона: дорогою від
   * тригера до картки курсор може перетнути сусідній тригер, і той картку не перехопить.
   */
  safeArea?: () => Element | null;
}

/**
 * Намір наведення для картки деталей (схема «розігріву»): перша картка відкривається після `openDelay`,
 * а поки якась картка відкрита (чи щойно закрилась) — наступна відкривається одразу, попередня зникає
 * без анімації. Закривається через `closeDelay` після того, як курсор пішов.
 *
 * З `safeArea` курсор, що покинув тригер у бік картки, тримає її відкритою (безпечний трикутник): інший тригер
 * на цьому шляху забирає картку, лише коли курсор на ньому зупинився (`HOVER_SWITCH_REST_MS`) або вийшов
 * за межі трикутника.
 */
export function useHoverIntent({ openDelay, closeDelay, safeArea }: HoverIntentOptions) {
  const [id] = useState(() => Symbol('hover-card'));
  const [state, setState] = useState<HoverIntentState>(CLOSED);
  const openRef = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const safeAreaRef = useRef(safeArea);
  useEffect(() => {
    safeAreaRef.current = safeArea;
  });

  const clear = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  }, []);

  const close = useCallback(
    (instant = false) => {
      clear();
      cancelPending(id);
      if (zone?.owner === id) endZone();
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
      cancelPending(id);
      for (const [other, closeOther] of openCards) if (other !== id) closeOther();
      openCards.set(id, () => close(true));
      openRef.current = true;
      setState({ open: true, instant, byHover });
    },
    [clear, close, id],
  );

  /** Курсор миші зайшов на тригер (чи на саму картку). `at` — де саме: для безпечної зони чужої картки. */
  const enter = useCallback(
    (at?: Point) => {
      clear();
      if (openRef.current) {
        // курсор дійшов до своєї картки чи повернувся на свій тригер: зона більше не потрібна
        if (zone?.owner === id) endZone();
        return;
      }
      if (zone && zone.owner !== id && at && inZone(at)) {
        // курсор іде до чужої картки через цей тригер: забираємо її, лише якщо він тут зупиниться
        pending = { id, take: () => show({ instant: true, byHover: true }) };
        armRest();
        return;
      }
      if (hoverCardsWarm()) show({ instant: true, byHover: true });
      else timer.current = setTimeout(() => show({ byHover: true }), openDelay);
    },
    [clear, id, openDelay, show],
  );

  const scheduleClose = useCallback(() => {
    clear();
    timer.current = setTimeout(() => close(), closeDelay);
  }, [clear, close, closeDelay]);

  /** Курсор миші пішов (з картки чи з тригера). */
  const leave = useCallback(() => {
    clear();
    cancelPending(id);
    if (!openRef.current) return;
    scheduleClose();
  }, [clear, id, scheduleClose]);

  /**
   * Курсор пішов із тригера в точці `at`. Є картка (`safeArea`) — будуємо безпечний трикутник до неї й тримаємо
   * картку, поки курсор у ньому; інакше — як `leave`.
   */
  const leaveTrigger = useCallback(
    (at?: Point) => {
      clear();
      cancelPending(id);
      if (!openRef.current) return;
      const card = safeAreaRef.current?.();
      const box = card?.getBoundingClientRect();
      // без розмітки (jsdom) чи коли курсор уже на картці — звичайне закриття із затримкою
      if (!at || !box || box.width === 0 || box.height === 0) {
        scheduleClose();
        return;
      }
      const rect = { left: box.left, top: box.top, right: box.right, bottom: box.bottom };
      startZone({ owner: id, ...safeTriangle(at, rect), rect, release: scheduleClose });
    },
    [clear, id, scheduleClose],
  );

  useEffect(
    () => () => {
      clear();
      cancelPending(id);
      if (zone?.owner === id) endZone();
      if (openCards.delete(id)) lastClosedAt = Date.now();
    },
    [clear, id],
  );

  return { ...state, enter, leave, leaveTrigger, show, close };
}
