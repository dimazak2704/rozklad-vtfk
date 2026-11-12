import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SEARCH_URL_DELAY_MS, useTableUrlState } from './useTableUrlState';

function wrapper(initial: string) {
  return ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>;
}

function useHarness() {
  return { table: useTableUrlState(null, ['active']), location: useLocation(), navigate: useNavigate() };
}

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe('пошук таблиці в адресі', () => {
  it('поле одразу показує кожну літеру, адреса оновлюється після паузи', () => {
    const { result } = renderHook(useHarness, { wrapper: wrapper('/rooms?page=2') });
    for (const value of ['3', '30', '301']) {
      act(() => result.current.table.set({ q: value }));
      expect(result.current.table.q).toBe(value);
    }
    expect(result.current.location.search).toBe('?page=2');
    act(() => {
      vi.advanceTimersByTime(SEARCH_URL_DELAY_MS);
    });
    expect(result.current.location.search).toBe('?q=301');
    expect(result.current.table.q).toBe('301');
    expect(result.current.table.filtered).toBe(true);
  });

  it('фільтр разом з недописаним пошуком не губить текст', () => {
    const { result } = renderHook(useHarness, { wrapper: wrapper('/rooms') });
    act(() => result.current.table.set({ q: 'Коваль' }));
    act(() => result.current.table.set({ active: 'true' }));
    expect(new URLSearchParams(result.current.location.search).get('q')).toBe('Коваль');
    expect(result.current.table.q).toBe('Коваль');
  });

  it('очищення — одразу; перехід за посиланням з іншим пошуком — поле його переймає', () => {
    const { result } = renderHook(useHarness, { wrapper: wrapper('/rooms?q=301') });
    expect(result.current.table.q).toBe('301');
    act(() => result.current.table.set({ q: null }));
    expect(result.current.location.search).toBe('');
    act(() => {
      void result.current.navigate('/rooms?q=205');
    });
    expect(result.current.table.q).toBe('205');
  });
});
