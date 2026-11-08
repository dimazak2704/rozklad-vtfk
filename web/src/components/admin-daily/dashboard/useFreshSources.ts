import { useCallback, useEffect, useRef, useState } from 'react';
import { useServerEvent } from '@/api/live';
import type { Uuid } from '@/api/types';

/** Скільки секунд нова подія лишається підсвіченою. */
const FRESH_MS = 6_000;

/**
 * Що змінилося наживо після відкриття сторінки: id змін, відсутностей і розпоряджень з подій SSE.
 * Кожне джерело підсвічується кілька секунд і гасне саме.
 */
export function useFreshSources(): ReadonlySet<Uuid> {
  const [fresh, setFresh] = useState<ReadonlySet<Uuid>>(new Set());
  const timers = useRef(new Map<Uuid, number>());

  const mark = useCallback((id: Uuid) => {
    setFresh((prev) => new Set(prev).add(id));
    const previous = timers.current.get(id);
    if (previous !== undefined) window.clearTimeout(previous);
    timers.current.set(
      id,
      window.setTimeout(() => {
        timers.current.delete(id);
        setFresh((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
      }, FRESH_MS),
    );
  }, []);

  useServerEvent('change.updated', (change) => mark(change.id));
  useServerEvent('absence.created', (absence) => mark(absence.id));
  useServerEvent('order.published', (order) => mark(order.id));

  useEffect(() => {
    const pending = timers.current;
    return () => {
      for (const timer of pending.values()) window.clearTimeout(timer);
      pending.clear();
    };
  }, []);

  return fresh;
}
