/**
 * Тонка обгортка над fetch для REST API через gateway.
 * - базовий шлях `/api` (у dev Vite проксіює на :8080, у Docker — nginx на gateway);
 * - додає `Authorization: Bearer …`, якщо користувач увійшов;
 * - розбирає ProblemDetail у типізований ApiError (errors[], conflicts[]).
 */
import { DEV_ROLES } from '@/auth/devUser';
import { noteUnsavedChange } from '@/auth/unsavedChange';
import { getAccessToken } from '@/auth/userManager';
import { ApiError, errorFromResponse } from './errors';
import { noteResponseSource } from './saved-copy';

export const API_BASE = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '');

/** Чи відповідає мок-API (src/mocks) замість бекенду: лише dev з VITE_USE_MOCKS=1, у production — завжди false. */
export const MOCK_API = import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS === '1';

/**
 * Мок-API з фіктивним персоналом (dev, ролі ADMIN/TEACHER без сервісу входу): справжнього токена немає, а мок
 * має бачити «увійшов» (архів замін і розкладу без обмежень). У production-збірці MOCK_API — false.
 */
const MOCK_STAFF_TOKEN = MOCK_API && DEV_ROLES.some((role) => role === 'ADMIN' || role === 'TEACHER') ? 'mock-dev-staff' : null;

export type QueryValue = string | number | boolean | null | undefined | ReadonlyArray<string | number>;
export type QueryParams = Record<string, QueryValue>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  query?: QueryParams;
  /** Обʼєкт → JSON; FormData/Blob/рядок — як є. */
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /**
   * Чи додавати токен (за замовчуванням так, якщо він є). `'required'` — відповідь залежить від входу і
   * кешується як відповідь персоналу: без дійсного токена (строк минув, оновити не вдалося) запит не йде як
   * гостьовий, а завершується 401, як від сервера, — обробник 401 забуває токен, і сторінка перепитує як гість.
   */
  auth?: boolean | 'required';
  /** Як читати відповідь. За замовчуванням JSON (204 → undefined); `file` — File з назвою від сервера. */
  responseType?: 'json' | 'blob' | 'text' | 'file';
  /** Обмеження часу в мс (за замовчуванням 30 с). */
  timeoutMs?: number;
  /**
   * Запит доживає до кінця, навіть якщо вкладку закрили (fetch keepalive, тіло до 64 КБ). На відміну від
   * navigator.sendBeacon, несе й заголовок Authorization.
   */
  keepalive?: boolean;
}

type UnauthorizedHandler = (error: ApiError) => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

/** Реакція на 401 (наприклад, повести на вхід). Повертає функцію відписки. */
export function onUnauthorized(handler: UnauthorizedHandler): () => void {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null;
  };
}

/** `/public/schedule/group/x` + query → `/api/public/schedule/group/x?from=…` */
export function buildUrl(path: string, query?: QueryParams): string {
  const normalized = path.startsWith('/') ? path : `/${path}`;
  const url = normalized.startsWith(`${API_BASE}/`) ? normalized : `${API_BASE}${normalized}`;
  if (!query) return url;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const item of value as ReadonlyArray<string | number>) params.append(key, String(item));
    } else {
      params.append(key, String(value));
    }
  }
  const search = params.toString();
  return search ? `${url}?${search}` : url;
}

function isBodyInit(body: unknown): body is BodyInit {
  return (
    typeof body === 'string' ||
    body instanceof FormData ||
    body instanceof Blob ||
    body instanceof URLSearchParams ||
    body instanceof ArrayBuffer
  );
}

function combineSignals(timeoutMs: number, external?: AbortSignal): { signal: AbortSignal; timedOut: () => boolean } {
  const controller = new AbortController();
  let didTimeout = false;
  const timer = setTimeout(() => {
    didTimeout = true;
    controller.abort();
  }, timeoutMs);
  const abort = () => controller.abort();
  if (external) {
    if (external.aborted) controller.abort();
    else external.addEventListener('abort', abort, { once: true });
  }
  controller.signal.addEventListener(
    'abort',
    () => {
      clearTimeout(timer);
      external?.removeEventListener('abort', abort);
    },
    { once: true },
  );
  return { signal: controller.signal, timedOut: () => didTimeout };
}

/**
 * Назва файлу з `Content-Disposition`: `filename*=UTF-8''…` (RFC 6266) має перевагу над `filename="…"`.
 * `null` — сервер назви не дав або заголовок не видно (інше походження без Access-Control-Expose-Headers).
 */
export function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const extended = /filename\*\s*=\s*[^']*'[^']*'([^;]+)/i.exec(header)?.[1]?.trim();
  if (extended) {
    try {
      return decodeURIComponent(extended);
    } catch {
      // зіпсоване кодування — беремо звичайну назву
    }
  }
  const plain = /filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]+))/i.exec(header);
  const name = plain?.[1]?.replace(/\\(.)/g, '$1') ?? plain?.[2]?.trim();
  return name || null;
}

/** 401: токен не прийнято або його немає — сторінка реагує (`onUnauthorized`), помилку отримує викликач. */
function unauthorized(error: ApiError, method: string): ApiError {
  // збереження, яке відхилили через вхід, не мовчить: після повторного входу буде пояснення
  if (method !== 'GET') noteUnsavedChange();
  unauthorizedHandler?.(error);
  return error;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', query, body, signal, auth = true, responseType = 'json', timeoutMs = 30_000, keepalive } = options;
  const headers = new Headers(options.headers);
  if (!headers.has('Accept')) {
    headers.set('Accept', responseType === 'json' ? 'application/json, application/problem+json' : '*/*');
  }

  let payload: BodyInit | undefined;
  if (body !== undefined && body !== null) {
    if (isBodyInit(body)) {
      payload = body;
    } else {
      payload = JSON.stringify(body);
      headers.set('Content-Type', 'application/json');
    }
  }

  if (auth) {
    const token = (await getAccessToken()) ?? MOCK_STAFF_TOKEN;
    if (token) headers.set('Authorization', `Bearer ${token}`);
    else if (auth === 'required') throw unauthorized(new ApiError({ status: 401, title: 'Сесія входу завершилася' }), method);
  }

  const { signal: combined, timedOut } = combineSignals(timeoutMs, signal);
  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), { method, headers, body: payload, signal: combined, ...(keepalive ? { keepalive } : {}) });
  } catch (cause) {
    if (timedOut()) throw new ApiError({ status: 0, kind: 'timeout', cause });
    if (signal?.aborted) throw new ApiError({ status: 0, kind: 'aborted', cause });
    throw new ApiError({ status: 0, kind: 'network', cause });
  }

  if (!response.ok) {
    const error = await errorFromResponse(response);
    throw error.isUnauthorized ? unauthorized(error, method) : error;
  }
  // відповідь могла прийти з кешу Service Worker, а не з сервера: сторінка має це сказати
  if (method === 'GET') noteResponseSource(buildUrl(path, query), response.headers);

  if (response.status === 204 || response.status === 205) return undefined as T;
  if (responseType === 'blob') return (await response.blob()) as T;
  if (responseType === 'file') {
    const blob = await response.blob();
    return new File([blob], fileNameFromDisposition(response.headers.get('Content-Disposition')) ?? '', { type: blob.type }) as T;
  }
  if (responseType === 'text') return (await response.text()) as T;

  const text = await response.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch (cause) {
    throw new ApiError({ status: response.status, kind: 'parse', cause });
  }
}

/** Скорочення для типових методів. */
export const api = {
  get: <T>(path: string, query?: QueryParams, options?: Omit<RequestOptions, 'method' | 'query' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'GET', query }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T = void>(path: string, options?: Omit<RequestOptions, 'method'>) =>
    apiRequest<T>(path, { ...options, method: 'DELETE' }),
  /** Завантаження файлу: multipart з полями. */
  upload: <T>(path: string, form: FormData, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { timeoutMs: 120_000, ...options, method: 'POST', body: form }),
  /** Файл як Blob (DOCX/PDF/PNG розпорядження, ICS). */
  blob: (path: string, query?: QueryParams, options?: Omit<RequestOptions, 'method' | 'query' | 'responseType'>) =>
    apiRequest<Blob>(path, { ...options, method: 'GET', query, responseType: 'blob' }),
  /** Файл як File: назва — з `Content-Disposition` відповіді, порожня, якщо сервер її не дав. */
  file: (path: string, query?: QueryParams, options?: Omit<RequestOptions, 'method' | 'query' | 'responseType'>) =>
    apiRequest<File>(path, { ...options, method: 'GET', query, responseType: 'file' }),
};
