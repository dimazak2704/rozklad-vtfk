import { describe, expect, it } from 'vitest';
import type { CalendarBellProfile, CalendarPeriod, CalendarSemester, CalendarWeek } from '@/api/calendar';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import { GROUPS } from '../fixtures';

async function failure(request: Promise<unknown>): Promise<ApiError> {
  try {
    await request;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Запит мав завершитися помилкою');
}

const semesters = () => api.get<CalendarSemester[]>('/admin/semesters');
const active = async () => (await semesters()).find((s) => s.active) as CalendarSemester;
const weeks = (id: string) => api.get<CalendarWeek[]>(`/admin/semesters/${id}/weeks`);

describe('семестри й тижні', () => {
  it('I семестр 2026/2027 активний: 18 навчальних тижнів, тиждень 19.10 — восьмий, знаменник', async () => {
    const semester = await active();
    expect(semester).toMatchObject({ name: 'I семестр 2026/2027', academicYear: '2026/2027', teachingWeeks: 18, firstWeekParity: 'NUMERATOR' });
    const week = (await weeks(semester.id)).find((w) => w.weekStart === '2026-10-19');
    expect(week).toEqual({
      weekStart: '2026-10-19',
      weekEnd: '2026-10-23',
      weekNumber: 8,
      parity: 'DENOMINATOR',
      computedParity: 'DENOMINATOR',
      overridden: false,
    });
  });

  it('перевизначення міняє лише один тиждень; та сама парність, що за чергуванням, знімає його', async () => {
    const semester = await active();
    const changed = await api.put<CalendarWeek>(`/admin/semesters/${semester.id}/weeks/2026-10-19`, { parity: 'NUMERATOR' });
    expect(changed).toMatchObject({ parity: 'NUMERATOR', computedParity: 'DENOMINATOR', overridden: true });
    const all = await weeks(semester.id);
    expect(all.find((w) => w.weekStart === '2026-10-26')).toMatchObject({ parity: 'NUMERATOR', overridden: false });
    const reset = await api.put<CalendarWeek>(`/admin/semesters/${semester.id}/weeks/2026-10-19`, { parity: 'DENOMINATOR' });
    expect(reset.overridden).toBe(false);
  });

  it('тиждень задається понеділком у межах семестру', async () => {
    const semester = await active();
    expect((await failure(api.put(`/admin/semesters/${semester.id}/weeks/2026-10-20`, { parity: 'NUMERATOR' }))).fieldErrors()).toEqual({
      weekStart: 'Тиждень задається датою його понеділка.',
    });
    expect((await failure(api.put(`/admin/semesters/${semester.id}/weeks/2027-03-01`, { parity: 'NUMERATOR' }))).fieldErrors().weekStart).toBe(
      'Тиждень 01.03.2027 не належить семестру.',
    );
  });

  it('активним буває лише один семестр, а активний не видаляється', async () => {
    const current = await active();
    const second = (await semesters()).find((s) => !s.active) as CalendarSemester;
    await api.post(`/admin/semesters/${second.id}/activate`);
    const after = await semesters();
    expect(after.filter((s) => s.active).map((s) => s.id)).toEqual([second.id]);
    expect((await failure(api.delete(`/admin/semesters/${second.id}`))).message).toBe('Активний семестр не можна видалити: спершу активуйте інший.');
    await api.post(`/admin/semesters/${current.id}/activate`);
  });

  it('новий семестр не може перетинати наявний', async () => {
    const error = await failure(api.post('/admin/semesters', { number: 1, academicYear: '2025/2026', startsOn: '2026-08-01', endsOn: '2026-09-15' }));
    expect(error.message).toBe('Дати семестру перетинаються з «I семестр 2026/2027» (01.09.2026–30.12.2026).');
  });
});

describe('календарні періоди', () => {
  it('практика 2КІ3 з розпорядження №35 уже є, з групою й приміткою', async () => {
    const semester = await active();
    const periods = await api.get<CalendarPeriod[]>('/admin/calendar-periods', { semesterId: semester.id });
    expect(periods.find((p) => p.kind === 'PRACTICE')).toMatchObject({
      title: 'Електрорадіомонтажна практика',
      groups: [{ code: '2КІ3' }],
      orderNote: '2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.',
    });
  });

  it('перевіряє, кого стосується період, режим навчання й межі семестру', async () => {
    const semester = await active();
    const error = await failure(
      api.post('/admin/calendar-periods', {
        semesterId: semester.id,
        kind: 'STUDY_MODE',
        title: 'Дистанційно',
        startsOn: '2026-08-25',
        endsOn: '2026-09-10',
        scope: 'GROUPS',
        groupIds: [],
      }),
    );
    expect(error.fieldErrors()).toEqual({
      groupIds: 'Вкажіть хоча б одну групу.',
      startsOn: 'Період має бути в межах семестру (01.09.2026–30.12.2026).',
      studyMode: 'Для періоду форми навчання вкажіть режим: офлайн, онлайн або змішаний.',
    });
  });

  it('створює період для групи з посиланнями на групи', async () => {
    const semester = await active();
    const group = GROUPS.find((g) => g.code === '3КІ1') as { id: string };
    const created = await api.post<CalendarPeriod>('/admin/calendar-periods', {
      semesterId: semester.id,
      kind: 'PRACTICE',
      title: 'Технологічна практика',
      startsOn: '2026-11-16',
      endsOn: '2026-11-27',
      scope: 'GROUPS',
      groupIds: [group.id],
      orderNote: '3КІ1 – Технологічна практика з 16.11.26р. по 27.11.26 р.',
    });
    expect(created).toMatchObject({ scope: 'GROUPS', courses: null, groups: [{ code: '3КІ1' }], studyMode: null, version: 0 });
  });
});

describe('профілі дзвінків', () => {
  it('накладання пар — 400 з полем слота', async () => {
    const error = await failure(
      api.post('/admin/bell-profiles', {
        name: 'Скорочені',
        slots: [
          { weekday: 1, pair: 1, startsAt: '08:30', endsAt: '09:30' },
          { weekday: 1, pair: 2, startsAt: '09:20', endsAt: '10:20' },
        ],
      }),
    );
    expect(error.fieldErrors()).toEqual({ 'slots[1].startsAt': 'Пара 2 у понеділок починається раніше, ніж закінчується пара 1.' });
  });

  it('типовий профіль не видаляється й не вимикається', async () => {
    const profiles = await api.get<CalendarBellProfile[]>('/admin/bell-profiles');
    const standard = profiles.find((p) => p.defaultProfile) as CalendarBellProfile;
    expect(standard.name).toBe('2 зміни, 60 хв');
    expect((await failure(api.delete(`/admin/bell-profiles/${standard.id}`))).message).toBe(
      'Профіль «2 зміни, 60 хв» — типовий у налаштуваннях: спершу виберіть інший.',
    );
    expect(
      (await failure(api.put(`/admin/bell-profiles/${standard.id}`, { name: standard.name, active: false, slots: standard.slots }))).message,
    ).toBe('Типовий профіль дзвінків не можна вимкнути: спершу виберіть інший у налаштуваннях.');
  });
});
