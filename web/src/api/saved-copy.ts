import { useSyncExternalStore } from 'react';
import { formatDayMonth, formatInstantTime, relativeDayLabel, todayInKyiv } from '@/lib/date';
import { SAVED_COPY_HEADER } from './saved-copy-plugin';

/**
 * Які відповіді API прийшли не з мережі, а з кешу Service Worker (позначка saved-copy-plugin.ts).
 * Ключ — адреса запиту, як її будує `buildUrl`; значення — коли копію збережено (мс) або null, якщо невідомо.
 * Свіжа відповідь з мережі для тієї самої адреси позначку прибирає.
 */
const copies = new Map<string, number | null>();
const listeners = new Set<() => void>();

function notify(): void {
  for (const listener of listeners) listener();
}

/** Викликає клієнт API для кожної успішної GET-відповіді. */
export function noteResponseSource(url: string, headers: Pick<Headers, 'get'>): void {
  const marker = headers.get(SAVED_COPY_HEADER);
  if (marker === null) {
    if (copies.delete(url)) notify();
    return;
  }
  const parsed = Date.parse(marker);
  const savedAt = Number.isNaN(parsed) ? null : parsed;
  if (copies.get(url) === savedAt) return;
  copies.set(url, savedAt);
  notify();
}

/** Для тестів: забути всі позначки. */
export function resetSavedCopies(): void {
  copies.clear();
  notify();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export type SavedCopy = { fromCache: false } | { fromCache: true; savedAt: number | null };

/** Чи останні дані за цією адресою — збережена копія Service Worker, а не відповідь сервера. */
export function useSavedCopy(url: string | undefined): SavedCopy {
  const snapshot = useSyncExternalStore(
    subscribe,
    () => (url === undefined || !copies.has(url) ? undefined : copies.get(url)),
    () => undefined,
  );
  return snapshot === undefined ? { fromCache: false } : { fromCache: true, savedAt: snapshot };
}

/**
 * Те саме для кількох запитів сторінки (головна: день, заміни, стан дня): чи хоч один прийшов з кешу.
 * Дата — найстарішої копії; якщо дата котроїсь невідома, то й загальна невідома.
 */
export function useAnySavedCopy(urls: readonly (string | undefined)[]): SavedCopy {
  const snapshot = useSyncExternalStore(
    subscribe,
    // примітив, а не обʼєкт: useSyncExternalStore порівнює знімки через Object.is
    () => oldestCopy(urls),
    () => undefined,
  );
  return snapshot === undefined ? { fromCache: false } : { fromCache: true, savedAt: snapshot };
}

function oldestCopy(urls: readonly (string | undefined)[]): number | null | undefined {
  let oldest: number | null | undefined;
  for (const url of urls) {
    if (url === undefined || !copies.has(url)) continue;
    const savedAt = copies.get(url) ?? null;
    if (savedAt === null || oldest === null) oldest = null;
    else oldest = oldest === undefined ? savedAt : Math.min(oldest, savedAt);
  }
  return oldest;
}

/** «сьогодні о 08:15», «вчора о 19:40», «10 листопада о 08:15». */
export function savedCopyTimeLabel(savedAt: number, now: Date = new Date()): string {
  const instant = new Date(savedAt).toISOString();
  const day = todayInKyiv(new Date(savedAt));
  const relative = relativeDayLabel(day, todayInKyiv(now));
  const dayLabel = relative === 'Сьогодні' || relative === 'Вчора' ? relative.toLowerCase() : formatDayMonth(day);
  return `${dayLabel} о ${formatInstantTime(instant)}`;
}
