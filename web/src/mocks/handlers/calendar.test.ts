import { describe, expect, it } from 'vitest';
import type { CalendarBellProfile, CalendarPeriod, CalendarSemester, CalendarWeek } from '@/api/calendar';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { PeriodKindDto, ScheduleDto } from '@/api/types';
import { GROUPS } from '../fixtures';
import { KRENTSIN } from './public';

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
      periodKind: { code: 'НП', name: 'Практика', effect: 'SUSPEND' },
      source: 'MANUAL',
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
      studyMode: 'Для виду з ефектом «онлайн» вкажіть режим: офлайн, онлайн або змішаний.',
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

  it('керівники практики: лише для PRACTICE, видні в розкладі групи й керівника', async () => {
    const semester = await active();
    const group = GROUPS.find((g) => g.code === '3КІ1') as { id: string };
    const body = {
      semesterId: semester.id,
      kind: 'NO_CLASSES',
      title: 'Технологічна практика',
      startsOn: '2026-11-16',
      endsOn: '2026-11-27',
      scope: 'GROUPS',
      groupIds: [group.id],
      supervisorIds: [KRENTSIN.id],
    };
    expect((await failure(api.post('/admin/calendar-periods', body))).fieldErrors()).toEqual({
      supervisorIds: 'Керівників призначають лише для виду, коли група не на парах (практика, НС…).',
    });
    expect((await failure(api.post('/admin/calendar-periods', { ...body, kind: 'PRACTICE', supervisorIds: ['nobody'] }))).fieldErrors()).toEqual({
      'supervisorIds[0]': 'Викладача не знайдено.',
    });
    const created = await api.post<CalendarPeriod>('/admin/calendar-periods', { ...body, kind: 'PRACTICE' });
    expect(created.supervisors).toEqual([KRENTSIN]);

    // від імені персоналу: гостю давніші за тиждень дні не віддаються, а тест не має залежати від дати запуску
    const staff = { headers: { Authorization: 'Bearer staff' } };
    const groupDay = await api.get<ScheduleDto>(`/public/schedule/group/${group.id}`, { from: '2026-11-16', to: '2026-11-16' }, staff);
    expect(groupDay.days[0]?.practices).toMatchObject([{ title: 'Технологічна практика', supervisors: [KRENTSIN], groups: [{ code: '3КІ1' }] }]);
    const teacherDay = await api.get<ScheduleDto>(`/public/schedule/teacher/${KRENTSIN.id}`, { from: '2026-11-16', to: '2026-11-16' }, staff);
    expect(teacherDay.days[0]?.practices).toMatchObject([{ title: 'Технологічна практика', kind: { name: 'Практика' }, room: null }]);
    const before = await api.get<ScheduleDto>(`/public/schedule/teacher/${KRENTSIN.id}`, { from: '2026-11-13', to: '2026-11-13' }, staff);
    expect(before.days[0]?.practices).toEqual([]);
  });
});

describe('періоди з видами з довідника', () => {
  const staff = { headers: { Authorization: 'Bearer staff' } };
  const kinds = () => api.get<PeriodKindDto[]>('/admin/period-kinds');
  const kindId = async (code: string) => ((await kinds()).find((k) => k.code === code) as PeriodKindDto).id;

  it('НС 4КІ1 з графіка: аудиторія 401, ключ імпорту; група 16.11 без пар, з блоком «Національний спротив»', async () => {
    const semester = await active();
    const periods = await api.get<CalendarPeriod[]>('/admin/calendar-periods', { semesterId: semester.id });
    const ons = periods.find((p) => p.periodKind?.code === 'НС');
    expect(ons).toMatchObject({
      kind: 'PRACTICE',
      title: 'Основи національного спротиву',
      startsOn: '2026-11-16',
      endsOn: '2026-11-27',
      room: { name: '401' },
      source: 'IMPORT',
      sourceKey: '2026/2027|НС|2026-11-16|2026-11-27|2a3c2b4f',
      groups: [{ code: '4КІ1' }],
    });
    const group = GROUPS.find((g) => g.code === '4КІ1') as { id: string };
    const day = (await api.get<ScheduleDto>(`/public/schedule/group/${group.id}`, { from: '2026-11-16', to: '2026-11-16' }, staff)).days[0];
    expect(day).toMatchObject({ studyDay: false, lessons: [], note: 'Основи національного спротиву' });
    expect(day?.practices).toMatchObject([{ title: 'Основи національного спротиву', kind: { code: 'НС', name: 'Національний спротив', effect: 'SUSPEND' }, room: { name: '401' } }]);
    const before = (await api.get<ScheduleDto>(`/public/schedule/group/${group.id}`, { from: '2026-11-13', to: '2026-11-13' }, staff)).days[0];
    expect(before?.lessons.length).toBeGreaterThan(0);
  });

  it('kindId важливіший за старий kind; старий kind — вбудований вид; без виду — 400 kindId', async () => {
    const semester = await active();
    const group = GROUPS.find((g) => g.code === '3КІ1') as { id: string };
    const body = { semesterId: semester.id, title: 'Збори', startsOn: '2026-12-01', endsOn: '2026-12-01', scope: 'GROUPS', groupIds: [group.id] };
    const zu = await api.post<CalendarPeriod>('/admin/calendar-periods', { ...body, kind: 'HOLIDAY', kindId: await kindId('ЗУ') });
    expect(zu).toMatchObject({ periodKind: { code: 'ЗУ' }, kind: 'PRACTICE', source: 'MANUAL', sourceKey: null });
    const session = await api.post<CalendarPeriod>('/admin/calendar-periods', { ...body, kind: 'EXAM_SESSION' });
    expect(session).toMatchObject({ periodKind: { code: 'І', name: 'Сесія', effect: 'SUSPEND' }, kind: 'EXAM_SESSION' });
    expect((await failure(api.post('/admin/calendar-periods', body))).fieldErrors()).toEqual({ kindId: 'Вкажіть вид періоду.' });
    expect((await failure(api.post('/admin/calendar-periods', { ...body, kindId: 'nope' }))).fieldErrors()).toEqual({ kindId: 'Вид періоду не знайдено.' });
  });

  it('аудиторія лише для «група не на парах»; режим — лише для «онлайн»', async () => {
    const semester = await active();
    const room = (await api.get<{ items: Array<{ id: string; name: string }> }>('/admin/rooms', { q: '401' })).items.find((r) => r.name === '401') as { id: string };
    const body = { semesterId: semester.id, title: 'Резерв', startsOn: '2026-12-01', endsOn: '2026-12-02', scope: 'ALL', roomId: room.id, studyMode: 'ONLINE' };
    expect((await failure(api.post('/admin/calendar-periods', { ...body, kindId: await kindId('Р') }))).fieldErrors()).toEqual({
      roomId: 'Аудиторію вказують лише для виду, коли група не на парах.',
      studyMode: 'Режим навчання задають лише для виду з ефектом «онлайн».',
    });
    expect((await failure(api.post('/admin/calendar-periods', { ...body, kindId: await kindId('НС'), studyMode: null, roomId: 'nope' }))).fieldErrors()).toEqual({
      roomId: 'Аудиторію не знайдено.',
    });
  });

  it('вимкнений вид не обирають для нового періоду, але наявний період з ним зберігається; правка імпортованого робить його ручним', async () => {
    const semester = await active();
    const ns = (await kinds()).find((k) => k.code === 'НС') as PeriodKindDto;
    await api.put(`/admin/period-kinds/${ns.id}`, { ...ns, active: false });
    const group = GROUPS.find((g) => g.code === '3КІ1') as { id: string };
    const error = await failure(
      api.post('/admin/calendar-periods', { semesterId: semester.id, kindId: ns.id, title: 'НС', startsOn: '2026-12-01', endsOn: '2026-12-02', scope: 'GROUPS', groupIds: [group.id] }),
    );
    expect(error.fieldErrors()).toEqual({ kindId: 'Вид «Національний спротив» вимкнено: увімкніть його в «Видах періодів» або виберіть інший.' });
    const ons = (await api.get<CalendarPeriod[]>('/admin/calendar-periods', { semesterId: semester.id })).find((p) => p.kindId === ns.id) as CalendarPeriod;
    const saved = await api.put<CalendarPeriod>(`/admin/calendar-periods/${ons.id}`, {
      semesterId: semester.id,
      kindId: ns.id,
      title: ons.title,
      startsOn: ons.startsOn,
      endsOn: '2026-11-26',
      scope: 'GROUPS',
      groupIds: ons.groupIds,
      roomId: ons.roomId,
      orderNote: ons.orderNote,
      version: ons.version,
    });
    expect(saved).toMatchObject({ source: 'MANUAL', sourceKey: ons.sourceKey, endsOn: '2026-11-26', room: { name: '401' } });
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
