import { useMemo, useSyncExternalStore } from 'react';
import type { BellSlotDto, IsoDate } from '@/api/types';
import { pairStatus, type PairStatus } from '@/lib/bells';
import { clockState, nowMs, subscribeClock } from '@/lib/clock';
import { kyivClock, type KyivClock } from '@/lib/date';

/**
 * Один спільний «годинник» на весь застосунок: усі підписники оновлюються разом. Час — з lib/clock, тож
 * на стенді з переведеним часом «зараз» той самий, що й на сервері.
 */
export const NOW_TICK_MS = 5_000;

const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;
let stopClock: (() => void) | null = null;
let snapshot = nowMs();

function tick(): void {
  snapshot = nowMs();
  for (const listener of listeners) listener();
}

function onVisibility(): void {
  if (document.visibilityState === 'visible') tick();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (timer === null) {
    snapshot = nowMs();
    timer = setInterval(tick, NOW_TICK_MS);
    document.addEventListener('visibilitychange', onVisibility);
    // годинник звірився з сервером (зсув стенда) — оновлюємо «зараз» одразу, не чекаючи такту
    stopClock = subscribeClock(tick);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
      document.removeEventListener('visibilitychange', onVisibility);
      stopClock?.();
      stopClock = null;
    }
  };
}

function getSnapshot(): number {
  return snapshot;
}

export interface NowState {
  /** Момент на годиннику застосунку (на стенді з переведеним часом — серверний). */
  now: Date;
  /**
   * Справжній момент браузера — для строків, які сервер теж рахує за справжнім часом: запрошення,
   * коди привʼязки Telegram. Без переведеного часу збігається з `now`.
   */
  realNow: Date;
  clock: KyivClock;
  /** Чи переглядаємо сьогоднішній день (якщо дату передано). */
  isToday: boolean;
  /** Стан дня за дзвінками; null — не сьогодні або дзвінків немає. */
  status: PairStatus | null;
}

export interface UseNowOptions {
  /** День, який зараз на екрані. Без нього вважаємо, що це сьогодні. */
  date?: IsoDate | null;
  /** Пари, які реально є в розкладі (щоб «закінчилися» рахувалося від останньої своєї пари). */
  onlyPairs?: ReadonlySet<number> | null;
}

/**
 * Поточний момент у Києві та стан пари за дзвінками.
 * `useNow(bells)` → «зараз 2 пара, до кінця 23 хв» / «перерва до 11:00».
 */
export function useNow(bells?: readonly BellSlotDto[] | null, options: UseNowOptions = {}): NowState {
  const timestamp = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const { date = null, onlyPairs = null } = options;

  return useMemo(() => {
    const now = new Date(timestamp);
    const realNow = new Date(timestamp - clockState().offsetMs);
    const clock = kyivClock(now);
    const isToday = date === null || date === clock.date;
    const status =
      isToday && bells && bells.length > 0 ? pairStatus(bells, clock.minutes + clock.seconds / 60, onlyPairs) : null;
    return { now, realNow, clock, isToday, status };
  }, [timestamp, bells, date, onlyPairs]);
}
