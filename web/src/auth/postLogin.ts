import type { Role } from '@/api/types';

/**
 * Керівні символи і зворотна скісна. Парсер URL браузера викидає табуляцію й переведення рядка,
 * а `\` читає як `/`, тож `/\t/evil.example` і `/\evil.example` стають адресою іншого сайту.
 */
// eslint-disable-next-line no-control-regex -- саме керівні символи тут і шукаємо
const UNSAFE_PATH_CHARS = /[\u0000-\u001F\u007F\\]/;
/** Умовне походження для розбору шляху: важить лише, чи шлях лишається на ньому. */
const PARSE_BASE = 'https://rozklad.invalid';

/** Чи безпечний шлях повернення (лише внутрішній, без `//host` і протоколів). */
export function isSafeReturnPath(path: unknown): path is string {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || UNSAFE_PATH_CHARS.test(path)) {
    return false;
  }
  try {
    const url = new URL(path, PARSE_BASE);
    // `/..//evil.example` після нормалізації дає `//evil.example`: роутер прочитав би це як інший сайт
    return url.origin === PARSE_BASE && !url.pathname.startsWith('//');
  } catch {
    return false;
  }
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

/** Стан, який передаємо сервісу входу і отримуємо назад після входу. */
export interface SigninState {
  returnTo?: string;
}

export function returnToFromState(state: unknown): string | undefined {
  if (typeof state !== 'object' || state === null) return undefined;
  const value = (state as SigninState).returnTo;
  return isSafeReturnPath(value) ? value : undefined;
}

/**
 * Повне відкриття сторінки після входу: AuthProvider підхоплює сесію з `sessionStorage` під час
 * завантаження, тож так сторінка бачить користувача, навіть коли обробка повернення зі сторінки входу не вдалася.
 */
export function openAfterLogin(path: string): void {
  window.location.replace(path);
}
