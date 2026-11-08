import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { ApiError } from '@/api/errors';
import type {
  AcceptInviteResponse,
  BellsDto,
  InviteInfoDto,
  LessonView,
  PublicChangesDto,
  PublicGroupDto,
  ScheduleDto,
} from '@/api/types';
import { groupNamed, roomNamed, teacherNamed } from '../fixtures';
import { orderNumberFor } from './public';

const get = <T>(path: string, query?: Record<string, string | number>) => api.get<T>(path, query, { auth: false });
const failure = (promise: Promise<unknown>) => promise.then(() => null, (e: unknown) => e as ApiError);

function at(instant: string): void {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(instant));
}

afterEach(() => {
  vi.useRealTimers();
});

/** Кожен викладач і кожна звичайна аудиторія — не більше одного заняття на пару (крім потоків і спортзалу). */
function expectNoClashes(lessons: LessonView[]): void {
  const active = lessons.filter((l) => l.kind !== 'CANCELLED');
  const seen = new Map<string, LessonView>();
  for (const lesson of active) {
    if (!lesson.teacher) continue;
    const key = `${lesson.teacher.id}|${lesson.pair}`;
    const other = seen.get(key);
    // потік: той самий викладач, дисципліна й аудиторія в кількох груп — це не конфлікт
    if (other && !(other.discipline.id === lesson.discipline.id && other.room?.id === lesson.room?.id)) {
      throw new Error(`Викладач ${lesson.teacher.shortName} двічі на ${lesson.pair} парі: ${other.group.code} і ${lesson.group.code}`);
    }
    seen.set(key, lesson);
  }
}

describe('довідники', () => {
  it('групи — у порядку друкованого розкладу, з курсом, спеціальністю і зміною', async () => {
    const groups = await get<PublicGroupDto[]>('/public/groups');
    expect(groups).toHaveLength(56);
    expect(groups[0]).toMatchObject({ code: '1ЕА1', course: 1, shift: 1 });
    expect(groups.find((g) => g.code === '4КІ1')).toMatchObject({
      specialty: { letters: 'КІ', name: 'Компʼютерна інженерія' },
      department: 'Компʼютерне відділення',
    });
    expect(groups.map((g) => g.course)).toEqual([...groups.map((g) => g.course)].sort((a, b) => a - b));
  });
});

describe('GET /api/public/bells', () => {
  it('без дати віддає всі профілі, з датою — дзвінки того дня', async () => {
    at('2026-11-09T08:00:00Z');
    const all = await get<BellsDto>('/public/bells');
    expect(all.profiles?.map((p) => p.name)).toEqual(['2 зміни, 60 хв', '2 зміни, 50 хв', '1 зміна, 80 хв']);
    const friday = await get<BellsDto>('/public/bells', { date: '2026-11-13' });
    expect(friday.slots).toHaveLength(6);
    expect(friday.slots[3]).toEqual({ pair: 4, startsAt: '12:20', endsAt: '13:20' });
    const saturday = await get<BellsDto>('/public/bells', { date: '2026-11-14' });
    expect(saturday.slots).toEqual([]);
    expect((await failure(get('/public/bells', { date: '14.11.2026' })))?.status).toBe(400);
  });
});

describe('розпорядження №35 (19.10.2026)', () => {
  it('у розкладі 4КІ1 СТП Кренціна замінено на НДЕКСМ Непийводи в 6 аудиторії («Зв.зам.»)', async () => {
    at('2026-11-09T08:00:00Z');
    const schedule = await get<ScheduleDto>(`/public/schedule/group/${groupNamed('4КІ1').id}`, { from: '2026-10-19', to: '2026-10-23' });
    const monday = schedule.days[0];
    expect(monday).toMatchObject({ date: '2026-10-19', parity: 'DENOMINATOR', weekdayLabel: 'ПОНЕДІЛОК' });
    const second = monday?.lessons.find((l) => l.pair === 2);
    expect(second).toMatchObject({
      kind: 'SUBSTITUTED',
      discipline: { shortName: 'НДЕКСМ' },
      teacher: { id: teacherNamed('Непийвода').id },
      room: { id: roomNamed('6').id },
      original: { discipline: { shortName: 'СТП' }, teacher: { id: teacherNamed('Кренцін').id } },
      reasonText: 'Зв.зам.',
    });
    // заміщений викладач бачить цю пару у своєму розкладі як заміну
    const krentsin = await get<ScheduleDto>(`/public/schedule/teacher/${teacherNamed('Кренцін').id}`, { from: '2026-10-19', to: '2026-10-19' });
    expect(krentsin.days[0]?.lessons.find((l) => l.group.code === '4КІ1' && l.pair === 2)?.kind).toBe('SUBSTITUTED');
  });

  it('заміни на дату — рядки розпорядження за курсом і кодом групи', async () => {
    at('2026-11-09T08:00:00Z');
    const changes = await get<PublicChangesDto>('/public/changes', { date: '2026-10-19' });
    expect(changes.order).toMatchObject({ number: 35 });
    expect(changes.weekdayLabel).toBe('ПОНЕДІЛОК');
    expect(changes.groups.map((g) => g.group.code)).toEqual(['1КІ2', '1ТК1', '4КІ1']);
    expect(changes.groups[0]?.items[0]).toEqual({
      pairLabel: '2',
      rowType: 'REPLACEMENT',
      from: { discipline: 'Ін.мова', teacher: 'Крисак Л.' },
      reasonText: 'Відп.',
      to: { discipline: 'Ін.мова', teacher: 'Прокопчук' },
      room: '110',
      subgroupNote: '1 підгрупа',
    });
    expect(changes.groups[1]?.items[0]).toMatchObject({ rowType: 'CANCELLATION', to: {}, room: null, reasonText: 'Відп.' });
  });

  it('номер розпорядження — порядковий навчальний день семестру', () => {
    expect(orderNumberFor('2026-09-01')).toBe(1);
    expect(orderNumberFor('2026-10-19')).toBe(35);
  });
});

describe('демо-зміни навколо «сьогодні»', () => {
  it('сьогодні розпорядження вже опубліковано, зміни без конфліктів', async () => {
    at('2026-11-09T08:00:00Z'); // понеділок, 10:00 у Києві
    const changes = await get<PublicChangesDto>('/public/changes', { date: '2026-11-09' });
    expect(changes.order).toMatchObject({ number: orderNumberFor('2026-11-09') });
    expect(changes.groups.length).toBeGreaterThanOrEqual(3);
    const kinds = changes.groups.flatMap((g) => g.items.map((i) => i.rowType));
    expect(kinds).toEqual(expect.arrayContaining(['REPLACEMENT', 'CANCELLATION', 'ROOM_CHANGE']));

    for (const code of changes.groups.map((g) => g.group.code)) {
      const schedule = await get<ScheduleDto>(`/public/schedule/group/${groupNamed(code).id}`, { from: '2026-11-09', to: '2026-11-09' });
      expect(schedule.days[0]?.lessons.some((l) => l.kind !== 'REGULAR'), code).toBe(true);
    }
  });

  it('новий викладач чи аудиторія заміни вільні в ту пару', async () => {
    at('2026-11-09T13:00:00Z'); // 15:00 — розпорядження на завтра теж опубліковане
    for (const date of ['2026-11-09', '2026-11-10']) {
      const changes = await get<PublicChangesDto>('/public/changes', { date });
      expect(changes.groups.length, date).toBeGreaterThan(0);
      for (const { group } of changes.groups) {
        const day = (await get<ScheduleDto>(`/public/schedule/group/${group.id}`, { from: date, to: date })).days[0];
        for (const lesson of day?.lessons.filter((l) => l.kind === 'SUBSTITUTED' || l.kind === 'ADDITIONAL') ?? []) {
          if (!lesson.teacher) continue;
          const teacherDay = (await get<ScheduleDto>(`/public/schedule/teacher/${lesson.teacher.id}`, { from: date, to: date })).days[0];
          const sameSlot = (teacherDay?.lessons ?? []).filter((l) => l.teacher?.id === lesson.teacher?.id);
          expect(() => expectNoClashes(sameSlot), `${date} ${group.code} ${lesson.pair}`).not.toThrow();
        }
      }
    }
  });

  it('розпорядження на завтра стає публічним о 14:20 напередодні', async () => {
    at('2026-11-09T10:00:00Z'); // 12:00 у Києві
    expect((await get<PublicChangesDto>('/public/changes', { date: '2026-11-10' })).order).toBeNull();
    at('2026-11-09T12:30:00Z'); // 14:30 у Києві
    const tomorrow = await get<PublicChangesDto>('/public/changes', { date: '2026-11-10' });
    expect(tomorrow.order?.publishedAt).toBe('2026-11-09T12:20:00.000Z');
    expect(tomorrow.groups.length).toBeGreaterThan(0);
  });

  it('у день без змін — порожньо і без розпорядження', async () => {
    at('2026-11-09T08:00:00Z');
    expect(await get<PublicChangesDto>('/public/changes', { date: '2026-10-05' })).toMatchObject({ order: null, groups: [] });
  });
});

describe('помилки розкладу', () => {
  it('400 на поганий період, 404 на невідомий тип чи власника', async () => {
    const id = groupNamed('4КІ1').id;
    expect((await failure(get(`/public/schedule/group/${id}`, { from: '2026-11-01', to: '2026-12-15' })))?.status).toBe(400);
    expect((await failure(get(`/public/schedule/group/${id}`)))?.status).toBe(400);
    expect((await failure(get(`/public/schedule/building/${id}`, { from: '2026-11-09', to: '2026-11-13' })))?.status).toBe(404);
    expect((await failure(get('/public/schedule/group/00000000-0000-4000-8000-000000000000', { from: '2026-11-09', to: '2026-11-13' })))?.status).toBe(404);
    expect((await failure(get('/public/changes')))?.status).toBe(400);
  });
});

describe('GET /api/public/ics', () => {
  it('календар групи: VTIMEZONE Києва, події з TZID, рядки не довші за 75 байтів', async () => {
    at('2026-11-09T08:00:00Z');
    const text = await api.get<string>(`/public/ics/group/${groupNamed('4КІ1').id}.ics`, undefined, { auth: false, responseType: 'text' });
    expect(text.startsWith('BEGIN:VCALENDAR\r\n')).toBe(true);
    expect(text).toContain('X-WR-CALNAME:Розклад 4КІ1');
    expect(text).toContain('TZID:Europe/Kyiv');
    expect(text).toMatch(/DTSTART;TZID=Europe\/Kyiv:20261019T094000/);
    const encoder = new TextEncoder();
    for (const line of text.split('\r\n')) expect(encoder.encode(line).length).toBeLessThanOrEqual(75);
    expect((await failure(api.get(`/public/ics/room/${roomNamed('6').id}.ics`, undefined, { auth: false, responseType: 'text' })))?.status).toBe(404);
  });
});

describe('запрошення', () => {
  it('чинне запрошення приймається один раз; логін перевіряється', async () => {
    expect(await get<InviteInfoDto>('/public/invites/demo')).toMatchObject({ valid: true, teacherName: 'Колесник А.' });

    const invalid = await failure(api.post('/public/invites/demo/accept', { username: 'Ab', password: 'short' }, { auth: false }));
    expect(invalid?.status).toBe(400);
    expect(Object.keys(invalid?.fieldErrors() ?? {})).toEqual(['password', 'username']);

    const taken = await failure(api.post('/public/invites/demo/accept', { username: 'krentsin', password: 'very-secret-1' }, { auth: false }));
    expect(taken?.status).toBe(409);
    expect(taken?.fieldErrors()).toEqual({ username: 'Цей логін уже зайнятий — оберіть інший' });

    const created = await api.post<AcceptInviteResponse>('/public/invites/demo/accept', { username: 'kolesnyk', password: 'very-secret-1' }, { auth: false });
    expect(created).toEqual({ username: 'kolesnyk' });
    expect((await get<InviteInfoDto>('/public/invites/demo')).valid).toBe(false);
    expect((await failure(api.post('/public/invites/demo/accept', { username: 'kolesnyk2', password: 'very-secret-1' }, { auth: false })))?.status).toBe(410);
  });

  it('прострочене — 410, невідоме — 404', async () => {
    expect((await get<InviteInfoDto>('/public/invites/demo-expired')).valid).toBe(false);
    expect((await failure(api.post('/public/invites/demo-expired/accept', { username: 'palamarchuk', password: 'very-secret-1' }, { auth: false })))?.status).toBe(410);
    expect((await failure(get('/public/invites/no-such-token')))?.status).toBe(404);
  });
});
