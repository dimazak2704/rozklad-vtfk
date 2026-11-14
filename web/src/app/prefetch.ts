import { matchPath } from 'react-router';
import { ACTIVE_ROUTES, type AppRoute } from './routes';

/**
 * Код сторінки вантажиться наперед, коли людина наводить курсор, торкається чи переходить клавішею Tab
 * на посилання: поки палець чи кнопка миші опуститься, частина коду сторінки вже в кеші браузера, і
 * запити даних стартують одразу після кліку, а не після ще одного кола до сервера за JS.
 */
const started = new Set<string>();

/** Маршрут реєстру для адреси (`/admin/orders?date=…` → запис «Розпорядження»). */
export function routeForPath(pathname: string, routes: readonly AppRoute[] = ACTIVE_ROUTES): AppRoute | undefined {
  return routes.find((route) => matchPath({ path: route.path, end: true }, pathname) !== null);
}

/** Починає завантаження коду сторінки один раз; помилку (немає мережі) покаже сам перехід. */
export function prefetchRoute(pathname: string, routes: readonly AppRoute[] = ACTIVE_ROUTES): void {
  const route = routeForPath(pathname, routes);
  if (!route || started.has(route.id)) return;
  started.add(route.id);
  route.page().catch(() => started.delete(route.id));
}

function internalPath(target: EventTarget | null, origin: string): string | undefined {
  const link = target instanceof Element ? target.closest('a[href]') : null;
  if (!(link instanceof HTMLAnchorElement) || link.target === '_blank' || link.hasAttribute('download')) return undefined;
  const url = new URL(link.href, origin);
  return url.origin === origin ? url.pathname : undefined;
}

/** Слухачі на весь документ (посилання бічної панелі, вкладок, списків); повертає відписку. */
export function installRoutePrefetch(root: Document = document, routes: readonly AppRoute[] = ACTIVE_ROUTES): () => void {
  const origin = root.location?.origin ?? window.location.origin;
  const onIntent = (event: Event) => {
    const path = internalPath(event.target, origin);
    if (path) prefetchRoute(path, routes);
  };
  const events = ['pointerover', 'focusin', 'touchstart'] as const;
  for (const type of events) root.addEventListener(type, onIntent, { passive: true });
  return () => {
    for (const type of events) root.removeEventListener(type, onIntent);
  };
}

/** Лише для тестів: забути, що вже завантажено. */
export function resetRoutePrefetch(): void {
  started.clear();
}
