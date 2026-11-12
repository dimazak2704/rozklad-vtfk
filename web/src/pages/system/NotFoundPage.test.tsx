import { screen } from '@testing-library/react';
import type { RouteObject } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routeTree } from '@/app/router';
import { renderWithProviders } from '@/test/render';
import NotFoundPage from './NotFoundPage';

function renderAt(path: string) {
  return renderWithProviders(<NotFoundPage />, { route: path });
}

/** Усі шляхи маршрутів під вузлом — щоб перевірити, у якому макеті живе «не знайдено». */
function paths(routes: RouteObject[] = []): string[] {
  return routes.flatMap((route) => [...(route.path ? [route.path] : []), ...paths(route.children)]);
}

describe('NotFoundPage', () => {
  it('у публічній частині веде на розклад', () => {
    renderAt('/nope');
    expect(screen.getByRole('link', { name: /Розклад/ })).toHaveAttribute('href', '/');
  });

  it('у панелі диспетчера веде на «Сьогодні», а не на публічний розклад', () => {
    renderAt('/admin/nope');
    expect(screen.getByRole('link', { name: /Сьогодні/ })).toHaveAttribute('href', '/admin');
  });

  it('у кабінеті викладача веде на «Мій розклад»', () => {
    renderAt('/me/nope');
    expect(screen.getByRole('link', { name: /Мій розклад/ })).toHaveAttribute('href', '/me');
  });

  it('невідомі адреси панелі й кабінету лишаються у своєму макеті', () => {
    const root = routeTree[0]?.children ?? [];
    const [publicLayout, teacherGate, adminGate] = root;
    expect(paths([publicLayout!])).toContain('*');
    expect(paths([teacherGate!])).toContain('/me/*');
    expect(paths([adminGate!])).toContain('/admin/*');
    expect(paths([publicLayout!])).not.toContain('/admin/*');
  });
});
