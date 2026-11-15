import { describe, expect, it } from 'vitest';
import { routesFor } from './routes';

describe('макети маршрутів', () => {
  it('вхід і його завершення — без оболонки сайту: шапка й підвал не блимають перед переходом', () => {
    const bare = routesFor('bare').map((route) => route.path);
    expect(bare).toEqual(expect.arrayContaining(['/login', '/login/callback']));
    expect(routesFor('public').map((route) => route.path)).not.toContain('/login');
  });
});
