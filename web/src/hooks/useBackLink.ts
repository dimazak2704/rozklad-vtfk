import { useMemo } from 'react';
import { matchPath, useLocation } from 'react-router';
import { ACTIVE_ROUTES, backLabel, parentRoute, type AppRoute } from '@/app/routes';

export interface BackLink {
  to: string;
  label: string;
  /** Сторінка в оболонці з бічною панеллю (адмінка): на ≥ 1024 px шлях і так видно в панелі. */
  sidebarShell?: boolean;
}

/** Маршрут реєстру для адреси: точні шляхи раніше за шляхи з параметрами. */
export function routeForPath(pathname: string): AppRoute | undefined {
  const ranked = [...ACTIVE_ROUTES].sort((a, b) => Number(a.path.includes(':')) - Number(b.path.includes(':')));
  return ranked.find((route) => matchPath({ path: route.path, end: true }, pathname));
}

/**
 * Куди веде «‹ Назад» з поточної сторінки: рівень вище за реєстром маршрутів (`parent` у routes.ts).
 * null — сторінка верхнього рівня свого розділу (головна, «Мій розклад», «Сьогодні» і розділи панелі диспетчера).
 * Працює з будь-яким роутером (і з MemoryRouter у тестах): бере лише адресу.
 */
export function useBackLink(): BackLink | null {
  const { pathname } = useLocation();
  return useMemo(() => {
    const route = routeForPath(pathname);
    const parent = parentRoute(route?.id);
    if (!parent) return null;
    // розділ панелі диспетчера (Заміни, Розклад, Календар…) — верхній рівень: шлях до інших розділів дає меню,
    // а «‹ Сьогодні» над заголовком читалося б як «ви всередині Сьогодні»
    if (route?.layout === 'admin' && parent.id === 'admin') return null;
    return {
      to: parent.path,
      label: backLabel(parent),
      sidebarShell: route?.layout === 'admin',
    };
  }, [pathname]);
}
