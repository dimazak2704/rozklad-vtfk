import { errorMessage, isApiError } from '@/api/errors';

/**
 * Помилки входу українською замість тексту браузера чи бібліотеки oidc-client-ts («Failed to fetch»,
 * «No matching state found in storage»): людині технічна причина нічого не каже.
 *
 * react-oidc-context віддає не саму помилку, а копію `{name, message, innerError}`, тож розрізняємо
 * за `name` (`TypeError` — мережа, `ErrorTimeout`, `ErrorResponse` — відповідь сервера входу), а не `instanceof`.
 */

interface ErrorLike {
  name?: unknown;
  message?: unknown;
  error?: unknown;
  innerError?: unknown;
}

function unwrap(error: unknown): ErrorLike | null {
  if (typeof error !== 'object' || error === null) return null;
  const like = error as ErrorLike;
  return typeof like.innerError === 'object' && like.innerError !== null ? like.innerError : like;
}

/** Код помилки OAuth (`access_denied`, `login_required`…) з відповіді сервера входу, якщо він є. */
function oauthCode(error: ErrorLike | null): string | null {
  return error?.name === 'ErrorResponse' && typeof error.error === 'string' ? error.error : null;
}

/** Чому не відкрилася сторінка входу коледжу (запуск входу на /login). */
export function loginErrorText(error: unknown): string {
  if (isApiError(error)) return errorMessage(error);
  const cause = unwrap(error);
  if (cause?.name === 'ErrorTimeout') return 'Сервер входу коледжу не відповів вчасно.';
  if (oauthCode(cause)) return 'Сервер входу коледжу відхилив запит.';
  // fetch без звʼязку: «Failed to fetch» (Chrome), «NetworkError…» (Firefox), «Load failed» (Safari)
  if (cause?.name === 'TypeError') return 'Немає звʼязку із сервером входу коледжу.';
  return 'Сервер входу коледжу не відповів або відхилив запит.';
}

/**
 * Чому не завершився вхід на /auth/callback. Код `error` з адреси повернення важливіший за помилку
 * бібліотеки: для скасованого входу з чужим `state` вона каже лише «No matching state».
 */
export function callbackErrorText(error: unknown, search = ''): string {
  const cause = unwrap(error);
  const code = new URLSearchParams(search).get('error') ?? oauthCode(cause);
  if (code === 'access_denied') return 'Вхід скасовано на сторінці входу коледжу.';
  if (code) return 'Сервер входу не підтвердив вхід.';
  const message = typeof cause?.message === 'string' ? cause.message.toLowerCase() : '';
  if (message.includes('state') || message.includes('code') || message.includes('expired')) {
    return 'Посилання входу застаріло або вже використане (наприклад, після кнопки «Назад» у браузері).';
  }
  if (cause?.name === 'TypeError') return 'Немає звʼязку із сервером входу коледжу.';
  return 'Сервер входу не відповів або відхилив запит.';
}

/**
 * Чи є в адресі повернення дані входу. Та сама умова, що й `hasAuthParams` у react-oidc-context:
 * без `code`/`error` разом зі `state` (у query чи в `#`) AuthProvider не завершує вхід і помилки не дає,
 * тож сторінка повернення мусить сама сказати, що входу тут немає.
 */
export function hasCallbackParams(search: string, hash = ''): boolean {
  return [search, hash.replace('#', '?')].some((part) => {
    const params = new URLSearchParams(part);
    return Boolean((params.get('code') || params.get('error')) && params.get('state'));
  });
}
