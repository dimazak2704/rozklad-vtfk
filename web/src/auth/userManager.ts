import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
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

/** Скільки секунд до кінця дії токена вже варто оновити його заздалегідь. */
export const RENEW_BEFORE_SECONDS = 30;

let renewing: Promise<User | null> | null = null;

/** Одне оновлення на всі паралельні запити. */
function renewOnce(manager: Pick<UserManager, 'signinSilent'>): Promise<User | null> {
  renewing ??= manager.signinSilent().finally(() => {
    renewing = null;
  });
  return renewing;
}

/**
 * Access token для запиту. Токен, що закінчився або от-от закінчиться (ноутбук спав довше за 15 хв,
 * таймер автооновлення не спрацював), спершу оновлюємо refresh token-ом, а не шлемо запит без
 * нього: інакше збереження отримало б 401, сторінка пішла б на вхід і правка зникла б. `null` —
 * користувач не увійшов або оновити не вдалося (тоді 401 веде на вхід, як і раніше).
 */
export async function getAccessToken(manager: Pick<UserManager, 'getUser' | 'signinSilent'> = userManager): Promise<string | null> {
  let user: User | null;
  try {
    user = await manager.getUser();
  } catch {
    return null;
  }
  if (!user) return null;
  const fresh = !user.expired && (user.expires_in === undefined || user.expires_in > RENEW_BEFORE_SECONDS);
  if (fresh || !user.refresh_token) return user.expired ? null : user.access_token;
  try {
    const renewed = await renewOnce(manager);
    if (renewed && !renewed.expired) return renewed.access_token;
  } catch {
    // refresh token відкликано або сервер входу недоступний — нижче вирішуємо за старим токеном
  }
  return user.expired ? null : user.access_token;
}
