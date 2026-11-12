import { useLayoutEffect, useRef, type RefObject } from 'react';

/** Те, що зараз на екрані: подання й дата (`{ view: 'week', at: '2026-11-09' }`), або null — іде завантаження. */
export interface NavKey {
  view: string;
  /** ISO-дата дня чи початку тижня: рядки порівнюються як дати. */
  at: string;
}

export type NavEnter = { kind: 'slide'; direction: 1 | -1 } | { kind: 'fade' } | null;

/** Скелет показується через 300 мс; коротше завантаження не «проявляємо», щоб не затримувати читання. */
export const SKELETON_DELAY_MS = 300;
const SLIDE_PX = 8;
const SLIDE_MS = 200;
const FADE_MS = 160;
const EASE_OUT = 'cubic-bezier(0.22, 1, 0.36, 1)';

/**
 * Як має увійти новий вміст (`previous` — останнє, що було на екрані до нього):
 * - той самий вид, інша дата — ковзає на 8 px з боку, куди людина гортала (пізніше — справа, раніше — зліва);
 * - інший вид (День ↔ Тиждень) — лише проявляється;
 * - після помітного завантаження (скелет був на екрані) — проявляється замість скелета;
 * - перший показ і те саме — без руху.
 */
export function navEnter(previous: NavKey | undefined, next: NavKey | null, loadedAfterSkeleton = false): NavEnter {
  if (!next) return null;
  const fadeIfWaited: NavEnter = loadedAfterSkeleton ? { kind: 'fade' } : null;
  if (!previous) return fadeIfWaited;
  if (previous.view !== next.view) return { kind: 'fade' };
  if (previous.at === next.at) return fadeIfWaited;
  return { kind: 'slide', direction: next.at > previous.at ? 1 : -1 };
}

function reducedMotion(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Ключові кадри входу: лише opacity і transform — композиція, без перерахунку макета. */
export function enterKeyframes(enter: Exclude<NavEnter, null>): Keyframe[] {
  if (enter.kind === 'fade') return [{ opacity: 0 }, { opacity: 1 }];
  return [
    { opacity: 0, transform: `translateX(${enter.direction * SLIDE_PX}px)` },
    { opacity: 1, transform: 'none' },
  ];
}

/**
 * Спокійний вхід дня чи тижня при навігації (Web Animations API, без перемонтування вмісту).
 * Повертає ref для обгортки вмісту. Без вподобання руху (prefers-reduced-motion) — нічого не рухається.
 */
export function useNavTransition<T extends HTMLElement>(key: NavKey | null): RefObject<T | null> {
  const ref = useRef<T | null>(null);
  const shown = useRef<NavKey | undefined>(undefined);
  const loadingSince = useRef<number | null>(null);
  const running = useRef<Animation | null>(null);
  const view = key?.view ?? null;
  const at = key?.at ?? null;

  useLayoutEffect(() => {
    if (view === null || at === null) {
      // завантаження (скелет): памʼятаємо, коли почалося, і що було до нього — напрямок рахуємо від того
      loadingSince.current ??= Date.now();
      return;
    }
    const next = { view, at };
    const waited = loadingSince.current !== null && Date.now() - loadingSince.current >= SKELETON_DELAY_MS;
    const enter = navEnter(shown.current, next, waited);
    shown.current = next;
    loadingSince.current = null;
    const el = ref.current;
    if (!enter || !el || typeof el.animate !== 'function' || reducedMotion()) return;
    running.current?.cancel();
    running.current = el.animate(enterKeyframes(enter), {
      duration: enter.kind === 'fade' ? FADE_MS : SLIDE_MS,
      easing: EASE_OUT,
    });
  }, [view, at]);

  return ref;
}
