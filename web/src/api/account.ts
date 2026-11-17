/**
 * Власний пароль (CONTRACTS §0.1): сервіс входу `auth` сам перевіряє поточний пароль і зберігає новий, тож запит
 * іде не через gateway (`/api`), а на `<видавець>/api/account/password` — на сервері це той самий сайт під `/auth`.
 * Токен — той самий access token SPA. Cookie сесії входу (`credentials: 'include'`) лише каже сервісу, яку сесію
 * лишити: інші сесії облікового запису після зміни пароля завершуються.
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
}

export interface ChangeOwnPasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** Адреса на сервісі входу: `http://localhost:8088/auth` + `/api/account/password`. */
export function accountUrl(path: string): string {
  return `${oidcConfig.authority.replace(/\/$/, '')}/api/account${path}`;
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

/** Стабільний код відмови сервісу входу: `urn:rozklad:auth:current-password-wrong` → `current-password-wrong`. */
export function authProblemCode(error: ApiError): string | undefined {
  if (error.code) return error.code;
  const type = error.type ?? '';
  return type.startsWith('urn:rozklad:auth:') ? type.slice('urn:rozklad:auth:'.length) : undefined;
}
