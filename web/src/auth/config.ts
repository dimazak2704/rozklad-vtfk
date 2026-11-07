/** Налаштування OIDC (Keycloak realm «rozklad», публічний клієнт з PKCE). */
const env = import.meta.env;

export const oidcConfig = {
  authority: env.VITE_OIDC_AUTHORITY || 'http://localhost:8180/realms/rozklad',
  clientId: env.VITE_OIDC_CLIENT_ID || 'rozklad-web',
  scope: env.VITE_OIDC_SCOPE || 'openid profile email',
  callbackPath: '/auth/callback',
} as const;
