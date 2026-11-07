import { http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { PublicMetaDto, SearchResultDto } from '@/api/types';
import { groupNamed, teacherNamed } from '../fixtures';
import { apiPath, problem } from '../kit';
import { server } from '../server';

/** Запит через справжній клієнт SPA — так перевіряємо і обробник, і формат відповіді. */
const getMeta = () => api.get<PublicMetaDto>('/public/meta', undefined, { auth: false });
const searchFor = (q: string, limit = 10) => api.get<SearchResultDto[]>('/public/search', { q, limit }, { auth: false });

function at(instant: string): void {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(instant));
}

afterEach(() => {
  vi.useRealTimers();
});

describe('GET /api/public/meta', () => {
  it('рахує тиждень, парність і поточну пару за київським часом', async () => {
    at('2026-10-19T07:00:00Z'); // понеділок, 10:00 у Києві — друга пара (09:40–10:40)
    const meta = await getMeta();
    expect(meta).toMatchObject({
      collegeName: 'Вінницький технічний фаховий коледж',
      today: '2026-10-19',
      weekNumber: 8,
      parity: 'DENOMINATOR',
      studyDay: true,
      currentPair: { number: 2, startsAt: '09:40', endsAt: '10:40', minutesLeft: 40 },
      nextPair: { number: 3, startsAt: '11:00', endsAt: '12:00' },
      bellProfile: { name: '2 зміни, 60 хв' },
    });
    expect(meta.semester).toMatchObject({ name: 'I семестр 2026/2027', startsOn: '2026-09-01', endsOn: '2026-12-30' });
  });

  it('на перерві показує лише наступну пару, у вихідний — жодної', async () => {
    at('2026-10-19T07:50:00Z'); // 10:50 — перерва перед третьою парою
    expect(await getMeta()).toMatchObject({ currentPair: null, nextPair: { number: 3 } });

    at('2026-11-07T09:00:00Z'); // субота
    expect(await getMeta()).toMatchObject({ today: '2026-11-07', studyDay: false, currentPair: null, nextPair: null });
  });

  it('у тесті можна підмінити відповідь помилкою ProblemDetail', async () => {
    server.use(http.get(apiPath('/public/meta'), () => problem({ status: 503, detail: 'Сервер оновлюється.' })));
    const error = await getMeta().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 503, detail: 'Сервер оновлюється.', type: 'urn:rozklad:problem:internal' });
    expect((error as ApiError).requestId).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('GET /api/public/search', () => {
  it('знаходить групу за кодом латиницею і веде на її сторінку', async () => {
    const [first] = await searchFor('4ki1');
    expect(first).toEqual({
      type: 'GROUP',
      id: groupNamed('4КІ1').id,
      label: '4КІ1',
      sublabel: '4 курс, Компʼютерна інженерія',
      url: '/g/4%D0%9A%D0%861',
    });
  });

  it('знаходить викладача за початком прізвища і в англійській розкладці', async () => {
    const krentsin = teacherNamed('Кренцін');
    for (const query of ['кренц', 'rhtywsy']) {
      const [first] = await searchFor(query);
      expect(first, query).toMatchObject({ type: 'TEACHER', id: krentsin.id, url: `/t/${krentsin.id}` });
      expect(first?.sublabel).toContain('СТП');
    }
  });

  it('знаходить аудиторію за номером, але не плутає 6 з 16', async () => {
    const results = await searchFor('6');
    expect(results[0]).toMatchObject({ type: 'ROOM', label: 'ауд. 6', sublabel: 'Аудиторія' });
    expect(results.map((r) => r.label)).not.toContain('ауд. 16');
  });

  it('обмежує кількість і повертає порожній список для порожнього запиту', async () => {
    expect(await searchFor('1к', 3)).toHaveLength(3);
    expect(await searchFor('   ')).toEqual([]);
  });

  it('відповідає 400 з помилкою поля на неправильний limit', async () => {
    const error = await searchFor('4ki1', 0).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).fieldErrors()).toEqual({ limit: 'Від 1 до 50' });
  });
});
