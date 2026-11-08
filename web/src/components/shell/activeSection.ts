import { matchPath } from 'react-router';
import type { AppRoute, NavMeta } from '@/app/routes';

/** Сторінки розкладу групи, викладача й аудиторії належать розділу «Розклад» (головна). */
const SCHEDULE_PATHS = ['/g/:code', '/t/:id', '/r/:id'];

/**
 * Який пункт навігації активний для адреси. Один збіг на всю навігацію: шапка й нижні вкладки
 * беруть його звідси, тож «Розклад» підсвічено і на /g/4КІ1, і рівно один пункт має aria-current.
 */
export function activeNavId(items: ReadonlyArray<AppRoute & { nav: NavMeta }>, pathname: string): string | null {
  // найдовший збіг виграє: /me/absences — «Відсутності», а не «Мій розклад»
  const ranked = [...items].sort((a, b) => b.path.length - a.path.length);
  for (const item of ranked) {
    const exact = item.path === '/' || item.path === '/me';
    if (matchPath({ path: item.path, end: exact }, pathname)) return item.id;
    if (item.path === '/' && SCHEDULE_PATHS.some((path) => matchPath({ path, end: true }, pathname))) return item.id;
  }
  return null;
}
