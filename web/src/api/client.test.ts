import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, buildUrl, onUnauthorized } from './client';
import { ApiError, errorMessage } from './errors';
import { takeUnsavedChange } from '@/auth/unsavedChange';

vi.mock('@/auth/userManager', () => ({
  getAccessToken: vi.fn(() => Promise.resolve('token-123')),
}));

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

describe('buildUrl', () => {
  it('додає базу /api і пропускає порожні параметри', () => {
    expect(buildUrl('/public/search', { q: '4ki1', limit: 10, empty: '', none: undefined })).toBe(
      '/api/public/search?q=4ki1&limit=10',
    );
  });

  it('повторює масиви як кілька параметрів', () => {
    expect(buildUrl('admin/changes', { status: ['PENDING_CONFIRMATION', 'DECLINED'] })).toBe(
      '/api/admin/changes?status=PENDING_CONFIRMATION&status=DECLINED',
    );
  });
});

describe('apiRequest', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    fetchMock.mockReset();
  });

  it('надсилає Bearer-токен і JSON-тіло', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, { id: 'c1' }));
    const result = await api.post<{ id: string }>('/admin/changes', { kind: 'CANCEL' });

    expect(result).toEqual({ id: 'c1' });
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe('/api/admin/changes');
    const headers = new Headers(init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer token-123');
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(init?.body).toBe('{"kind":"CANCEL"}');
  });

  it('не додає токен, коли auth: false', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, []));
    await api.get('/public/groups', undefined, { auth: false });
    const headers = new Headers(fetchMock.mock.calls[0]?.[1]?.headers);
    expect(headers.has('Authorization')).toBe(false);
  });

  it('повертає undefined на 204', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(api.delete('/admin/teachers/1')).resolves.toBeUndefined();
  });

  it('перетворює ProblemDetail з конфліктами на ApiError', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        409,
        {
          type: 'https://rozklad/errors/conflict',
          title: 'Конфлікт у розкладі',
          status: 409,
          detail: 'Викладач уже має пару в цей час',
          conflicts: [{ kind: 'TEACHER', severity: 'HARD', message: 'Кренцін зайнятий', lessonIds: ['l1'], pair: 2 }],
        },
        { 'X-Request-Id': 'req-7' },
      ),
    );

    const error = await api.post('/admin/changes', {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.isConflict).toBe(true);
    expect(apiError.message).toBe('Викладач уже має пару в цей час');
    expect(apiError.conflicts[0]?.kind).toBe('TEACHER');
    expect(apiError.requestId).toBe('req-7');
  });

  it('віддає помилки полів для форми', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(400, {
        title: 'Помилка валідації',
        status: 400,
        errors: [
          { field: 'username', message: 'Логін уже зайнятий' },
          { field: 'password', message: 'Щонайменше 8 символів' },
        ],
      }),
    );
    const error = (await api.post('/public/invites/t/accept', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.isValidation).toBe(true);
    expect(error.fieldErrors()).toEqual({ username: 'Логін уже зайнятий', password: 'Щонайменше 8 символів' });
    expect(error.isRetryable).toBe(false);
  });

  it('дає українське повідомлення, коли сервер не надіслав detail', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>502</html>', { status: 502 }));
    const error = (await api.get('/public/meta').catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(502);
    expect(error.message).toMatch(/Сервер тимчасово недоступний/);
    expect(error.isRetryable).toBe(true);
  });

  it('позначає мережевий збій окремим видом помилки', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const error = (await api.get('/public/meta').catch((e: unknown) => e)) as ApiError;
    expect(error.kind).toBe('network');
    expect(error.isRetryable).toBe(true);
  });

  it('викликає обробник 401', async () => {
    const handler = vi.fn();
    const off = onUnauthorized(handler);
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { status: 401 }));
    await api.get('/me').catch(() => undefined);
    expect(handler).toHaveBeenCalledOnce();
    expect(takeUnsavedChange()).toBe(false);
    off();
  });

  it('збереження, відхилене з 401, позначається, щоб після входу сказати «не збережено»', async () => {
    const off = onUnauthorized(vi.fn());
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { status: 401 }));
    await api.put('/admin/settings', { signatoryPosition: 'x' }).catch(() => undefined);
    expect(takeUnsavedChange()).toBe(true);
    off();
  });
});

describe('errorMessage', () => {
  it('додає до 409 перші конфлікти розкладу, щоб було видно, що саме заважає', () => {
    const conflict = (message: string) => ({ kind: 'GROUP' as const, severity: 'HARD' as const, message, lessonIds: [], pair: 3 });
    const error = new ApiError({
      status: 409,
      detail: 'Календар не змінено.',
      conflicts: [conflict('4КІ2 ОПЦЗ, 29.12: є заміна.'), conflict('А.'), conflict('Б.'), conflict('В.')],
    });
    expect(errorMessage(error)).toBe('Календар не змінено. 4КІ2 ОПЦЗ, 29.12: є заміна. А. Б. І ще 1.');
    expect(errorMessage(new ApiError({ status: 409, detail: 'Конфлікт.' }))).toBe('Конфлікт.');
  });
});
