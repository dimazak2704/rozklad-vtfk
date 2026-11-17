import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { PublicHeader } from './PublicHeader';

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({
    status: 'authenticated',
    user: { name: 'Кренцін Максим Дмитрович', username: 'krentsin' },
    isAdmin: false,
    isTeacher: true,
    isDevBypass: false,
    signOut: () => undefined,
  }),
}));

const classes = (element: Element) => element.className.split(/\s+/);

describe('шапка кабінету: права група не стискає пошук до однієї літери', () => {
  it('поле пошуку — лише коли правій групі вистачає 22 rem, інакше кнопка-іконка з Ctrl K у підказці', () => {
    renderWithProviders(<PublicHeader layout="teacher" />, { route: '/me' });
    const tools = document.querySelector('[data-header-tools]')!;
    // рішення ухвалює ширина групи праворуч від розділів (контейнер), а не ширина вікна
    expect(classes(tools)).toContain('@container/tools');
    const field = screen.getByRole('button', { name: 'Пошук: Група, викладач, аудиторія' });
    expect(classes(field)).toEqual(expect.arrayContaining(['hidden', 'lg:@[22rem]/tools:flex', 'min-w-0']));
    expect(classes(field)).not.toContain('min-w-28');
    const icon = screen.getByRole('button', { name: 'Пошук розкладу' });
    expect(classes(icon)).toEqual(expect.arrayContaining(['shrink-0', 'lg:@[22rem]/tools:hidden']));
  });

  it('аватар — коло 44 px, яке тісна шапка не стискає в овал', () => {
    renderWithProviders(<PublicHeader layout="teacher" />, { route: '/me' });
    const avatar = screen.getByRole('button', { name: 'Обліковий запис: Кренцін Максим Дмитрович' });
    expect(classes(avatar)).toEqual(expect.arrayContaining(['size-11', 'rounded-full', 'shrink-0']));
    expect(classes(avatar)).not.toContain('rounded-control');
  });

  it('на головній великого поля в шапці немає, а кнопка-іконка — лише вужче за 1024 px', () => {
    renderWithProviders(<PublicHeader />, { route: '/' });
    expect(screen.queryByRole('button', { name: 'Пошук: Група, викладач, аудиторія' })).toBeNull();
    const icon = screen.getByRole('button', { name: 'Пошук розкладу' });
    expect(classes(icon)).toContain('lg:hidden');
    expect(classes(icon)).not.toContain('lg:@[22rem]/tools:hidden');
  });
});
