/**
 * Налаштування OIDC: сервіс входу коледжу (services/auth) на тому ж походженні під `/auth`,
 * публічний клієнт `rozklad-web` з PKCE. Vite (5180) ходить на стенд у контейнері: `.env.development`
 * задає `VITE_OIDC_AUTHORITY=http://localhost:8088/auth`.
 */
const env = import.meta.env;

export const oidcConfig = {
  authority: env.VITE_OIDC_AUTHORITY || `${window.location.origin}/auth`,
  clientId: env.VITE_OIDC_CLIENT_ID || 'rozklad-web',
  scope: env.VITE_OIDC_SCOPE || 'openid profile email',
  callbackPath: '/login/callback',
  /** Сторінка у прихованому iframe, куди сервіс входу повертає код тихого оновлення (prompt=none). */
  silentPath: '/login/silent.html',
} as const;
