import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { useBackLink } from './useBackLink';

const at =
  (route: string) =>
  ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>;

describe('«‹ Назад» над заголовком', () => {
  it('розділи панелі диспетчера — верхній рівень: без «‹ Сьогодні» (шлях дає меню)', () => {
    for (const route of ['/admin/timetable', '/admin/changes', '/admin/calendar', '/admin/catalog/teachers']) {
      expect(renderHook(() => useBackLink(), { wrapper: at(route) }).result.current).toBeNull();
    }
  });

  it('публічні сторінки так само ведуть рівнем вище', () => {
    expect(renderHook(() => useBackLink(), { wrapper: at('/g/4КІ1') }).result.current).toMatchObject({ to: '/' });
  });
});
