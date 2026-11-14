import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AuthContext, type AuthContextProps } from 'react-oidc-context';
import { describe, expect, it, vi } from 'vitest';
import { useCurrentUser } from './useCurrentUser';

/** Порядок дій виходу: прибирання кешу PWA має закінчитися до переходу на сервіс входу. */
const steps = vi.hoisted((): string[] => []);

vi.mock('@/api/public-api-cache', () => ({
  dropAuthorizedCopies: () => {
    steps.push('кеш без відповідей з токеном');
    return Promise.resolve();
  },
}));

describe('useCurrentUser', () => {
  it('вихід спершу прибирає з кешу PWA відповіді на запити з токеном, а тоді веде на сервіс входу', async () => {
    const signoutRedirect = vi.fn(() => {
      steps.push('сервіс входу');
      return Promise.resolve();
    });
    const auth = { isLoading: false, isAuthenticated: false, user: null, signoutRedirect } as unknown as AuthContextProps;
    const { result } = renderHook(() => useCurrentUser(), {
      wrapper: ({ children }: { children: ReactNode }) => <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>,
    });

    await result.current.signOut();

    expect(steps).toEqual(['кеш без відповідей з токеном', 'сервіс входу']);
  });
});
