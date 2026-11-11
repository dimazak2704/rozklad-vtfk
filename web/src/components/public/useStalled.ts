import { useEffect, useState } from 'react';

/** Те, що потрібно від запиту TanStack Query, щоб зрозуміти, чи він «завис». */
export interface StallableQuery {
  isPending: boolean;
  fetchStatus: 'fetching' | 'paused' | 'idle';
}

/** `offline` — запит на паузі без мережі; `slow` — даних немає вже довше за поріг; null — усе гаразд. */
export type Stall = 'offline' | 'slow' | null;

/** Скільки чекати першу відповідь, перш ніж сказати людині, що щось не так (силует не має висіти вічно). */
export const STALL_AFTER_MS = 10_000;

/**
 * Чи «завис» хоч один запит без даних. Вимкнений запит (fetchStatus `idle`) не рахується. Пауза без мережі
 * (TanStack Query не повторює запит, поки браузер офлайн) ніколи не стане помилкою — тож без цього хука
 * людина бачила б силует нескінченно.
 */
export function useStalled(queries: readonly (StallableQuery | null | undefined)[], afterMs = STALL_AFTER_MS): Stall {
  const waiting = queries.filter((q): q is StallableQuery => Boolean(q && q.isPending && q.fetchStatus !== 'idle'));
  const paused = waiting.some((q) => q.fetchStatus === 'paused');
  const busy = waiting.length > 0;
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    if (!busy) return undefined;
    const timer = setTimeout(() => setSlow(true), afterMs);
    return () => {
      clearTimeout(timer);
      setSlow(false);
    };
  }, [busy, afterMs]);

  if (paused) return 'offline';
  return busy && slow ? 'slow' : null;
}
