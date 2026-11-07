import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { oidcConfig } from './config';

/**
 * Єдиний екземпляр UserManager: його використовує і React (AuthProvider), і API-клієнт,
 * щоб брати свіжий access token поза компонентами.
 * Тихе оновлення — через refresh token (Keycloak видає його публічним клієнтам), без iframe.
 */
export const userManager = new UserManager({
  authority: oidcConfig.authority,
  client_id: oidcConfig.clientId,
  redirect_uri: `${window.location.origin}${oidcConfig.callbackPath}`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: oidcConfig.scope,
  automaticSilentRenew: true,
  monitorSession: false,
  loadUserInfo: false,
  // sessionStorage: токен живе лише у вкладці й не переживає закриття браузера
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

/** Access token, якщо користувач увійшов і токен ще дійсний. */
export async function getAccessToken(): Promise<string | null> {
  try {
    const user = await userManager.getUser();
    return user && !user.expired ? user.access_token : null;
  } catch {
    return null;
  }
}
