import { useEffect } from 'react';

const SITE = 'Розклад ВТФК';

/**
 * Уточнює заголовок вкладки («4КІ1 — розклад групи — Розклад ВТФК»). Ефект сторінки виконується після
 * ефекту оболонки (RootLayout), тож уточнення не перезаписується; null — лишити заголовок маршруту.
 */
export function useDocumentTitle(title: string | null | undefined): void {
  useEffect(() => {
    // головна просить саме імʼя сайту — без «Розклад ВТФК — Розклад ВТФК»
    if (title) document.title = title === SITE ? SITE : `${title} — ${SITE}`;
  }, [title]);
}
