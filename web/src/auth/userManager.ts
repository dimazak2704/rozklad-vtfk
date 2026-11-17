import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { oidcConfig } from './config';

/**
 * Єдиний екземпляр UserManager: його використовує і React (AuthProvider), і API-клієнт,
 * щоб брати свіжий access token поза компонентами.
 *
 * Refresh token-а публічний клієнт не отримує: тихе оновлення йде прихованим iframe на
 * `/auth/oauth2/authorize?prompt=none` (сесія сервісу входу в cookie) з поверненням на
 * `/login/silent.html`. Сесії вже немає — сервер відповідає `login_required`, і користувача забуваємо.
 */
export const userManager = new UserManager({
  authority: oidcConfig.authority,
  client_id: oidcConfig.clientId,
  redirect_uri: `${window.location.origin}${oidcConfig.callbackPath}`,
  silent_redirect_uri: `${window.location.origin}${oidcConfig.silentPath}`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: oidcConfig.scope,
  automaticSilentRenew: true,
  monitorSession: false,
  loadUserInfo: false,
  // sessionStorage: токен живе лише у вкладці й не переживає закриття браузера
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

/** Відповіді prompt=none, які означають «сесії входу більше немає»: тихо вже не оновити. */
const SESSION_ENDED = new Set(['login_required', 'interaction_required', 'consent_required', 'account_selection_required']);

/** Чи сказав сервер входу, що сесії немає (вийшли, відкликали доступ, минув строк). */
export function isSessionEnded(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const { name, error: code } = error as { name?: unknown; error?: unknown };
  return name === 'ErrorResponse' && typeof code === 'string' && SESSION_ENDED.has(code);
}

/**
 * Сесії входу немає: забуваємо користувача у вкладці. Далі сторінка бачить гостя, а перший запит,
 * якому потрібен вхід, веде на /login (сервер входу покаже форму). Мережеві збої сюди не потрапляють:
 * тоді токен живе до кінця свого строку.
 */
export function forgetEndedSession(error: unknown, manager: Pick<UserManager, 'removeUser'> = userManager): void {
  if (isSessionEnded(error)) void manager.removeUser().catch(() => undefined);
}

userManager.events.addSilentRenewError((error) => forgetEndedSession(error));

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
 * Тихо оновлює токени зараз, а не перед кінцем строку. Потрібно після повернення з повторного входу через Google:
 * сервіс входу тоді дає сесії новий id, а `sid` старого ID token-а лишається від попереднього, і вихід з ним
 * сервер відхилив би (сесія жила б далі). Нові токени несуть новий `sid`, а якщо людина обрала інший обліковий
 * запис Google, вкладка переходить на нього. Сесії немає — користувача забуваємо; мережевий збій нічого не ламає.
 */
export async function renewSession(manager: Pick<UserManager, 'signinSilent' | 'removeUser'> = userManager): Promise<void> {
  try {
    await renewOnce(manager);
  } catch (error) {
    forgetEndedSession(error, manager);
  }
}

/**
 * Access token для запиту. Токен, що закінчився або от-от закінчиться (ноутбук спав довше за строк
 * токена, таймер автооновлення не спрацював), спершу тихо оновлюємо, а не шлемо запит без нього:
 * інакше збереження отримало б 401, сторінка пішла б на вхід і правка зникла б. `null` — користувач
 * не увійшов або оновити не вдалося (тоді 401 веде на вхід, як і раніше).
 */
export async function getAccessToken(
  manager: Pick<UserManager, 'getUser' | 'signinSilent' | 'removeUser'> = userManager,
): Promise<string | null> {
  let user: User | null;
  try {
    user = await manager.getUser();
  } catch {
    return null;
  }
  if (!user) return null;
  const fresh = !user.expired && (user.expires_in === undefined || user.expires_in > RENEW_BEFORE_SECONDS);
  if (fresh) return user.access_token;
  try {
    const renewed = await renewOnce(manager);
    if (renewed && !renewed.expired) return renewed.access_token;
  } catch (error) {
    // сесії входу немає — забуваємо користувача; сервер входу недоступний — вирішуємо за старим токеном
    if (isSessionEnded(error)) {
      forgetEndedSession(error, manager);
      return null;
    }
  }
  return user.expired ? null : user.access_token;
}
