import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Короткий «пульс» на елементах за id: `pulse(id)` додає id у набір і прибирає його через `durationMs`.
 * Таймери живуть разом із компонентом: розмонтування їх скасовує.
 */
export function usePulse<Id>(durationMs: number): [ReadonlySet<Id>, (id: Id) => void] {
  const [pulsing, setPulsing] = useState<ReadonlySet<Id>>(() => new Set());
  const timers = useRef(new Set<number>());

  useEffect(() => {
    const active = timers.current;
    return () => {
      for (const timer of active) window.clearTimeout(timer);
      active.clear();
    };
  }, []);

  const pulse = useCallback(
    (id: Id) => {
      setPulsing((prev) => new Set(prev).add(id));
      const timer = window.setTimeout(() => {
        timers.current.delete(timer);
        setPulsing((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
      }, durationMs);
      timers.current.add(timer);
    },
    [durationMs],
  );

  return [pulsing, pulse];
}
