/**
 * Годинник застосунку для доменного часу: «сьогодні», тиждень і парність, поточна пара, відлік до кінця,
 * дедлайни підтверджень. Зазвичай це час браузера. На тестовому стенді час можна «перевести»
 * (`scripts/dev-stack.sh up --time 2026-11-20T10:00`): тоді GET /api/public/meta віддає `standTime: true`
 * і серверний момент `now`, а ми тримаємо зсув `now − час браузера`, щоб сайт жив у тому самому дні й
 * годині, що й сервер.
 *
 * Не через цей годинник: токени входу (oidc-client-ts), кеш і повтори запитів, таймери анімацій і
 * сповіщень, строки дії запрошень і кодів Telegram (сервер теж рахує їх за справжнім часом). Там —
 * звичайний `Date.now()`.
 */
import { useSyncExternalStore } from 'react';

export interface ClockState {
  /** Чи стенд працює у переведеному часі. */
  standTime: boolean;
  /** Мілісекунди, які додаємо до часу браузера; 0 у звичайному режимі. */
  offsetMs: number;
  /**
   * Росте, коли час стрибнув (увімкнули чи вимкнули переведений час, або зсув змінився більше ніж на
   * хвилину). Корінь застосунку перемальовує сторінку з нуля, щоб «сьогодні» перерахували всі.
   */
  epoch: number;
}

/** Розбіжність, меншу за цю, вважаємо мережевим шумом: зсув оновлюємо тихо, сторінку не перемальовуємо. */
export const CLOCK_JUMP_MS = 60_000;

const STORAGE_KEY = 'rozklad.stand-clock';
const REAL: ClockState = { standTime: false, offsetMs: 0, epoch: 0 };

let state: ClockState = restore();
const listeners = new Set<() => void>();

/** Поточний момент на годиннику застосунку (мс від епохи). */
export function nowMs(): number {
  return Date.now() + state.offsetMs;
}

/** Поточний момент на годиннику застосунку. */
export function now(): Date {
  return new Date(nowMs());
}

/** Справжній момент (наприклад, коли браузер отримав дані) → той самий момент на годиннику застосунку. */
export function toClockMs(realMs: number): number {
  return realMs + state.offsetMs;
}

export function clockState(): ClockState {
  return state;
}

export function subscribeClock(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Стан годинника в компоненті: перемальовує, коли зсув змінився. */
export function useClockState(): ClockState {
  return useSyncExternalStore(subscribeClock, clockState, clockState);
}

/**
 * Звіряє годинник із відповіддю /api/public/meta. `sentAt`/`receivedAt` — справжній час браузера до й після
 * запиту: серверний `now` відноситься до середини цього проміжку, тож затримка мережі не зсуває годинник.
 */
export function syncClock(meta: { now?: string | null; standTime?: boolean | null }, sentAt: number, receivedAt: number): void {
  if (meta.standTime !== true) {
    apply(false, 0);
    return;
  }
  const server = meta.now ? Date.parse(meta.now) : Number.NaN;
  // стенд каже «час переведено», але момент незрозумілий — лишаємо годинник як був, а не вигадуємо
  if (!Number.isFinite(server)) return;
  apply(true, Math.round(server - (sentAt + receivedAt) / 2));
}

/** Лише для тестів: справжній час і порожнє сховище. */
export function resetClock(): void {
  state = REAL;
  storage()?.removeItem(STORAGE_KEY);
  notify();
}

function apply(standTime: boolean, offsetMs: number): void {
  if (standTime === state.standTime && offsetMs === state.offsetMs) return;
  const jumped = standTime !== state.standTime || Math.abs(offsetMs - state.offsetMs) > CLOCK_JUMP_MS;
  state = { standTime, offsetMs, epoch: jumped ? state.epoch + 1 : state.epoch };
  persist();
  notify();
}

function notify(): void {
  for (const listener of listeners) listener();
}

/**
 * Зсув памʼятаємо між завантаженнями: стенд рахує час як «справжній + сталий зсув», тож після
 * перезавантаження сторінка одразу відкривається в потрібному дні, без перемальовування після meta.
 */
function persist(): void {
  const store = storage();
  if (!store) return;
  if (state.standTime) store.setItem(STORAGE_KEY, JSON.stringify({ offsetMs: state.offsetMs }));
  else store.removeItem(STORAGE_KEY);
}

function restore(): ClockState {
  try {
    const raw = storage()?.getItem(STORAGE_KEY);
    if (!raw) return REAL;
    const parsed = JSON.parse(raw) as { offsetMs?: unknown };
    return typeof parsed.offsetMs === 'number' && Number.isFinite(parsed.offsetMs)
      ? { standTime: true, offsetMs: parsed.offsetMs, epoch: 0 }
      : REAL;
  } catch {
    return REAL;
  }
}

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}
