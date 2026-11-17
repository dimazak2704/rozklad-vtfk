/**
 * Власний пароль (CONTRACTS §0.1): сервіс входу `auth` сам перевіряє поточний пароль і зберігає новий, тож запит
 * іде не через gateway (`/api`), а на `<видавець>/api/account/password` — на сервері це той самий сайт під `/auth`.
 * Токен — той самий access token SPA. Cookie сесії входу (`credentials: 'include'`) лише каже сервісу, яку сесію
 * лишити: інші сесії облікового запису після зміни пароля завершуються.
 *
 * Обліковий запис без пароля (вхід лише через Google) встановлює перший пароль сам, але одного токена для цього
 * замало: сесія входу цього браузера має бути свіжо підтверджена через Google (`reauthRequired` у стані). Для цього
 * браузер іде на `<видавець>/oauth2/authorization/google?reauth_return=<ця сторінка>?password=set`, а сервіс входу
 * повертає його сюди, і меню знову відкриває вікно пароля.
 */
import { useMutation, useQuery } from '@tanstack/react-query';
import { oidcConfig } from '@/auth/config';
import { getAccessToken } from '@/auth/userManager';
import { ApiError, errorFromResponse } from './errors';
import { MOCK_API } from './client';

/** `CHANGEABLE` — є пароль; `NO_PASSWORD` — вхід лише через Google; `DEMO` — спільний демо-доступ сервера. */
export type OwnPasswordStatus = 'CHANGEABLE' | 'NO_PASSWORD' | 'DEMO';

export interface OwnPasswordInfoDto {
  status: OwnPasswordStatus;
  minLength: number;
  maxLength: number;
  /** Лише `NO_PASSWORD`: перш ніж встановити пароль, цей браузер має ще раз увійти через Google. */
  reauthRequired?: boolean;
}

export interface SetInitialPasswordRequest {
  newPassword: string;
}

export interface ChangeOwnPasswordRequest {
  currentPassword: string;
  newPassword: string;
}

function authorityBase(): string {
  return oidcConfig.authority.replace(/\/$/, '');
}

/** Адреса на сервісі входу: `http://localhost:8088/auth` + `/api/account/password`. */
export function accountUrl(path: string): string {
  return `${authorityBase()}/api/account${path}`;
}

/** Позначка в адресі застосунку: людина повернулася з підтвердження через Google, вікно пароля відкрити знову. */
export const PASSWORD_RETURN_PARAM = 'password';
export const PASSWORD_RETURN_VALUE = 'set';

/** Поточна сторінка з `?password=set` — куди сервіс входу поверне людину після Google. */
export function passwordReturnUrl(href: string = window.location.href): string {
  const url = new URL(href);
  url.searchParams.set(PASSWORD_RETURN_PARAM, PASSWORD_RETURN_VALUE);
  url.hash = '';
  return url.toString();
}

/** Початок повторного входу через Google; сервіс входу приймає лише адреси походжень SPA. */
export function googleReauthUrl(returnTo: string): string {
  return `${authorityBase()}/oauth2/authorization/google?reauth_return=${encodeURIComponent(returnTo)}`;
}

/** Перехід сторінки (окремо, щоб тести могли його підмінити: jsdom не вміє переходити). */
export const pageNavigation = {
  assign: (url: string): void => window.location.assign(url),
};

/**
 * Веде браузер на повторний вхід через Google і назад. У мок-режимі сервісу входу й Google немає: мок позначає
 * «щойно підтверджено» і сторінка одразу повертається на ту саму адресу з позначкою.
 */
export async function startGoogleReauth(returnTo: string = passwordReturnUrl()): Promise<void> {
  if (MOCK_API) {
    await fetch(`${authorityBase()}/mock/google-reauth`, { method: 'POST' });
    pageNavigation.assign(returnTo);
    return;
  }
  pageNavigation.assign(googleReauthUrl(returnTo));
}

/** Ключі TanStack Query цієї області (не перетинаються з `qk`: інший сервіс). */
export const accountKeys = {
  password: () => ['auth-account', 'password'] as const,
};

async function authRequest<T>(path: string, init: { method: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal }): Promise<T> {
  const token = await getAccessToken();
  // мок-API (dev без сервісу входу) токена не має і не перевіряє його
  if (!token && !MOCK_API) throw new ApiError({ status: 401, title: 'Сесія входу завершилася' });
  const headers = new Headers({ Accept: 'application/json, application/problem+json' });
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body !== undefined) headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(accountUrl(path), {
      method: init.method,
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      credentials: 'include',
      signal: init.signal,
    });
  } catch (cause) {
    if (init.signal?.aborted) throw new ApiError({ status: 0, kind: 'aborted', cause });
    throw new ApiError({ status: 0, kind: 'network', cause });
  }
  if (!response.ok) throw await errorFromResponse(response);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/** Чи може людина змінити пароль тут і які межі довжини діють. */
export function useOwnPasswordInfo(enabled = true) {
  return useQuery({
    queryKey: accountKeys.password(),
    queryFn: ({ signal }) => authRequest<OwnPasswordInfoDto>('/password', { method: 'GET', signal }),
    enabled,
    staleTime: 60_000,
  });
}

export function useChangeOwnPassword() {
  return useMutation({
    mutationFn: (request: ChangeOwnPasswordRequest) => authRequest<void>('/password', { method: 'POST', body: request }),
  });
}

/** Перший пароль облікового запису, що входить лише через Google (потрібне свіже підтвердження через Google). */
export function useSetInitialPassword() {
  return useMutation({
    mutationFn: (request: SetInitialPasswordRequest) =>
      authRequest<void>('/initial-password', { method: 'POST', body: request }),
  });
}

/** Стабільний код відмови сервісу входу: `urn:rozklad:auth:current-password-wrong` → `current-password-wrong`. */
export function authProblemCode(error: ApiError): string | undefined {
  if (error.code) return error.code;
  const type = error.type ?? '';
  return type.startsWith('urn:rozklad:auth:') ? type.slice('urn:rozklad:auth:'.length) : undefined;
}
