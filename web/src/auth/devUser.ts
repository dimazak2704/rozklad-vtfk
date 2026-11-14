import type { Role } from '@/api/types';
import { devRoles } from './roles';

function sessionStorageOrNull(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    // сховище вимкнене налаштуваннями браузера
    return null;
  }
}

/**
 * Ролі фіктивного dev-користувача (VITE_DEV_AUTH_ROLES, у мок-режимі — ще й sessionStorage), порожньо — без нього.
 * Одне значення на вкладку: його читають і useCurrentUser, і API-клієнт (мок-API має бачити «увійшов»).
 * import.meta.env.DEV у production-збірці замінюється на false, і гілка зникає з бандла.
 */
export const DEV_ROLES: readonly Role[] = import.meta.env.DEV
  ? devRoles(import.meta.env.VITE_DEV_AUTH_ROLES, import.meta.env.VITE_USE_MOCKS === '1', sessionStorageOrNull())
  : [];
