import { useMemo, useSyncExternalStore } from 'react';
import type { BellSlotDto, IsoDate } from '@/api/types';
import { pairStatus, type PairStatus } from '@/lib/bells';
import { kyivClock, type KyivClock } from '@/lib/date';

/** Один спільний «годинник» на весь застосунок: усі підписники оновлюються разом. */
export const NOW_TICK_MS = 5_000;

const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;
let snapshot = Date.now();

function tick(): void {
  snapshot = Date.now();
  for (const listener of listeners) listener();
}

function onVisibility(): void {
  if (document.visibilityState === 'visible') tick();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (timer === null) {
    snapshot = Date.now();
    timer = setInterval(tick, NOW_TICK_MS);
    document.addEventListener('visibilitychange', onVisibility);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
      document.removeEventListener('visibilitychange', onVisibility);
    }
  };
}

function getSnapshot(): number {
  return snapshot;
}

export interface NowState {
  now: Date;
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
    const clock = kyivClock(now);
    const isToday = date === null || date === clock.date;
    const status =
      isToday && bells && bells.length > 0 ? pairStatus(bells, clock.minutes + clock.seconds / 60, onlyPairs) : null;
    return { now, clock, isToday, status };
  }, [timestamp, bells, date, onlyPairs]);
}
