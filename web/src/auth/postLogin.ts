import type { Role } from '@/api/types';

/** Чи безпечний шлях повернення (лише внутрішній, без `//host` і протоколів). */
export function isSafeReturnPath(path: unknown): path is string {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') && !path.includes('\\');
}

/** Куди може зайти користувач з цими ролями. */
export function canAccessPath(path: string, roles: readonly Role[]): boolean {
  const pathname = path.split(/[?#]/)[0] ?? '/';
  if (pathname === '/admin' || pathname.startsWith('/admin/')) return roles.includes('ADMIN');
  if (pathname === '/me' || pathname.startsWith('/me/')) return roles.includes('TEACHER') || roles.includes('ADMIN');
  return true;
}

/**
 * Куди вести після входу: на сторінку, з якої прийшли (якщо вона дозволена), інакше
 * ADMIN → /admin, TEACHER → /me, решта → на головну.
 */
export function postLoginPath(roles: readonly Role[], returnTo?: unknown): string {
  if (isSafeReturnPath(returnTo) && returnTo !== '/' && !returnTo.startsWith('/login') && !returnTo.startsWith('/auth/')) {
    if (canAccessPath(returnTo, roles)) return returnTo;
  }
  if (roles.includes('ADMIN')) return '/admin';
  if (roles.includes('TEACHER')) return '/me';
  return '/';
}

/** Стан, який передаємо в Keycloak і отримуємо назад після входу. */
export interface SigninState {
  returnTo?: string;
}

export function returnToFromState(state: unknown): string | undefined {
  if (typeof state !== 'object' || state === null) return undefined;
  const value = (state as SigninState).returnTo;
  return isSafeReturnPath(value) ? value : undefined;
}
