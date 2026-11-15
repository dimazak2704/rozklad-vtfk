import { describe, expect, it } from 'vitest';
import { navFor, routeById, routesFor } from './routes';

describe('макети маршрутів', () => {
  it('вхід і його завершення — без оболонки сайту: шапка й підвал не блимають перед переходом', () => {
    const bare = routesFor('bare').map((route) => route.path);
    expect(bare).toEqual(expect.arrayContaining(['/login', '/login/callback']));
    expect(routesFor('public').map((route) => route.path)).not.toContain('/login');
  });

  it('«Сповіщення»: вкладка кабінету після «Побажань» і пункт панелі диспетчера поруч із Telegram', () => {
    const cabinet = routeById('me-notifications');
    expect(cabinet).toMatchObject({ path: '/me/notifications', layout: 'teacher', parent: 'me', roles: ['TEACHER', 'ADMIN'] });
    expect(navFor('teacher').filter((route) => route.nav.tab).map((route) => route.id)).toEqual([
      'me',
      'me-absences',
      'me-preferences',
      'me-notifications',
    ]);

    const admin = routeById('admin-notifications');
    expect(admin).toMatchObject({ path: '/admin/notifications', layout: 'admin', roles: ['ADMIN'], nav: { section: 'Планування' } });
    const planning = navFor('admin').map((route) => route.id);
    expect(planning.indexOf('admin-notifications')).toBe(planning.indexOf('admin-telegram') + 1);
  });
});
