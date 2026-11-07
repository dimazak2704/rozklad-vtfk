import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { eventStreamResponse, emitServerEvent, openEventStreams } from './events';
import { apiPath, controlHandler, paginate, parseDelay, problem, simulatedFailure } from './kit';
import { collectHandlers } from './registry';
import { server } from './server';
import { createMockCollection, resetMockStores } from './store';

// відносні шляхи обробників MSW розвʼязує від адреси сторінки (у jsdom — http://localhost:3000/)
const request = (path: string) => new Request(new URL(apiPath(path), window.location.href));

describe('ProblemDetail', () => {
  it('має формат бекенду: urn-тип, requestId у тілі й заголовку, помилки за полем', async () => {
    const response = problem({
      status: 400,
      detail: 'Перевірте поля.',
      errors: [
        { field: 'name', message: 'Обовʼязкове поле' },
        { field: 'code', message: 'Занадто довгий' },
      ],
    });
    expect(response.status).toBe(400);
    expect(response.headers.get('Content-Type')).toBe('application/problem+json');
    const body = (await response.json()) as Record<string, unknown>;
    expect(body).toMatchObject({ type: 'urn:rozklad:problem:validation', title: 'Некоректні дані запиту', status: 400 });
    expect(body.requestId).toBe(response.headers.get('X-Request-Id'));
    expect((body.errors as Array<{ field: string }>).map((e) => e.field)).toEqual(['code', 'name']);
  });
});

describe('симуляція збоїв і затримки', () => {
  it('прапорець mockError повертає помилку з потрібним статусом', async () => {
    const response = simulatedFailure(request('/public/meta?mockError=503'));
    expect(response?.status).toBe(503);
    expect(await response?.json()).toMatchObject({ type: 'urn:rozklad:problem:internal' });
    expect(simulatedFailure(request('/public/meta?mockError=network'))?.type).toBe('error');
    expect(simulatedFailure(request('/public/meta'))).toBeNull();
  });

  it('mockErrorPath обмежує збій одним ендпоінтом, а хибний прапорець — помилка', () => {
    expect(simulatedFailure(request('/public/meta?mockError=500&mockErrorPath=/public/search'))).toBeNull();
    expect(simulatedFailure(request('/public/search?mockError=500&mockErrorPath=/public/search'))?.status).toBe(500);
    expect(() => simulatedFailure(request('/public/meta?mockError=oops'))).toThrow(/400–599/);
  });

  it('розбирає затримку', () => {
    expect(parseDelay('1500')).toBe(1500);
    expect(parseDelay('real')).toBe('real');
    expect(parseDelay('infinite')).toBe('infinite');
    expect(parseDelay('-1')).toBeNull();
    expect(parseDelay(undefined)).toBeNull();
  });

  it('прослойка пропускає запит далі, якщо збою немає', async () => {
    server.use(controlHandler, http.get(apiPath('/test/ping'), () => HttpResponse.json({ ok: true })));
    const ok = await fetch(request('/test/ping?mockDelay=0'));
    expect(await ok.json()).toEqual({ ok: true });
    const failed = await fetch(request('/test/ping?mockDelay=0&mockError=409'));
    expect(failed.status).toBe(409);
  });
});

describe('пагінація', () => {
  const items = Array.from({ length: 45 }, (_, i) => i);

  it('ріже за page і size як Spring Data', () => {
    expect(paginate(items, new URL('http://x/?page=2&size=20'))).toEqual({ items: [40, 41, 42, 43, 44], page: 2, size: 20, total: 45 });
    expect(paginate(items, new URL('http://x/')).items).toHaveLength(20);
  });

  it('кидає 400 на неправильні параметри', () => {
    expect(() => paginate(items, new URL('http://x/?size=500'))).toThrow(Response);
  });
});

describe('сховища', () => {
  const groups = createMockCollection('Групу', () => [{ id: 'a', code: '4КІ1' }]);

  it('змінюються і повертаються до початкових даних', () => {
    groups.insert({ id: 'b', code: '4КІ2' });
    groups.update('a', (g) => ({ ...g, code: '4КІ9' }));
    expect(groups.all().map((g) => g.code)).toEqual(['4КІ9', '4КІ2']);
    resetMockStores();
    expect(groups.all()).toEqual([{ id: 'a', code: '4КІ1' }]);
  });

  it('відповідають 404 на відсутній запис', async () => {
    let thrown: unknown;
    try {
      groups.get('missing');
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(Response);
    expect(await (thrown as Response).json()).toMatchObject({ status: 404, detail: 'Групу з id missing не знайдено.' });
  });
});

describe('реєстр обробників', () => {
  it('вимагає експорт handlers і збирає модулі за назвою файлу', () => {
    const a = http.get('/a', () => HttpResponse.text('a'));
    const b = http.get('/b', () => HttpResponse.text('b'));
    expect(collectHandlers({ './handlers/b.ts': { handlers: [b] }, './handlers/a.ts': { handlers: [a] } })).toEqual([a, b]);
    expect(() => collectHandlers({ './handlers/x.ts': {} })).toThrow(/handlers/);
  });
});

describe('потік подій SSE', () => {
  it('тримає зʼєднання і доставляє події, поки клієнт не відʼєднається', async () => {
    const reader = (eventStreamResponse().body as ReadableStream<Uint8Array>).getReader();
    const decoder = new TextDecoder();
    expect(decoder.decode((await reader.read()).value)).toContain('retry: 5000');
    expect(openEventStreams()).toBe(1);

    emitServerEvent('schedule.changed', { dates: ['2026-11-09'], groupIds: [], teacherIds: [], reason: 'ORDER_PUBLISHED' });
    const frame = decoder.decode((await reader.read()).value);
    expect(frame).toMatch(/^id: \d+\nevent: schedule\.changed\ndata: \{"dates":\["2026-11-09"\]/);

    await reader.cancel();
    expect(openEventStreams()).toBe(0);
  });
});
