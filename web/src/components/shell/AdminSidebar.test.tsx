import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { AdminSidebar } from './AdminSidebar';

describe('бічна панель диспетчера', () => {
  it('згорнута рейка: іконки по центру, вибраний пункт з тлом (клас не губиться в підказці)', () => {
    renderWithProviders(<AdminSidebar collapsed onToggleCollapsed={() => undefined} />, { route: '/admin/changes' });
    const changes = screen.getByRole('link', { name: 'Заміни' });
    expect(changes.className).toContain('justify-center');
    expect(changes.className).toContain('bg-selected');
    expect(changes.className).not.toMatch(/=>|isActive/);
    expect(changes).toHaveAttribute('aria-current', 'page');
    const today = screen.getByRole('link', { name: 'Сьогодні' });
    expect(today.className).toContain('justify-center');
    expect(today.className).not.toContain('bg-selected');
    expect(today).not.toHaveAttribute('aria-current');
  });

  it('розгорнута: підпис поруч з іконкою, «Сьогодні» підсвічене лише на /admin', () => {
    renderWithProviders(<AdminSidebar collapsed={false} />, {
      route: '/admin',
    });
    const today = screen.getByRole('link', { name: 'Сьогодні' });
    expect(today.className).toContain('bg-selected');
    expect(today.className).not.toContain('justify-center');
    expect(screen.getByRole('link', { name: 'Заміни' }).className).not.toContain('bg-selected');
  });

  it('розділ, підсвічений за префіксом («Довідники» на сторінці викладачів), — поточний і для читача екрана', () => {
    renderWithProviders(<AdminSidebar collapsed={false} />, { route: '/admin/catalog/teachers' });
    const catalog = screen.getByRole('link', { name: 'Довідники' });
    expect(catalog.className).toContain('bg-selected');
    expect(catalog).toHaveAttribute('aria-current', 'true');
  });
});
