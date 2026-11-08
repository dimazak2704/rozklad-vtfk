import { useCallback, useState } from 'react';
import { flushSync } from 'react-dom';

/** Що друкувати: тиждень (A4 альбомно) або табличку з QR-кодом на двері (A4 книжково). */
export type PrintMode = 'week' | 'door';

/**
 * Друк сторінки розкладу. За замовчуванням (і для Ctrl+P) друкується тиждень; табличка на двері —
 * лише за явним вибором, після друку аркуш повертається до тижня.
 */
export function usePrintMode(): { mode: PrintMode; print: (mode: PrintMode) => void } {
  const [mode, setMode] = useState<PrintMode>('week');
  const print = useCallback((next: PrintMode) => {
    // аркуш має бути в DOM до того, як браузер зробить знімок сторінки для друку
    flushSync(() => setMode(next));
    window.addEventListener('afterprint', () => setMode('week'), { once: true });
    window.print();
  }, []);
  return { mode, print };
}
