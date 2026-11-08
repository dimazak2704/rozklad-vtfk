import { describe, expect, it } from 'vitest';
import type { LessonView } from '@/api/types';
import {
  bellSlots,
  buildSchedule,
  CALENDAR_PERIODS,
  DEPARTMENTS,
  DISCIPLINES,
  findGroupByCode,
  GROUPS,
  isStudyDay,
  lessonsOn,
  MAX_SCHEDULE_DAYS,
  parityOn,
  ROOMS,
  scheduleRangeError,
  semesterWeekNumber,
  semesterWeeks,
  SHIFT_WINDOWS,
  SPECIALTIES,
  TEACHERS,
  teacherNamed,
  TEMPLATE_LESSONS,
  roomNamed,
  groupNamed,
} from '.';
import { mockId } from '../ids';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function pairsOf(lessons: readonly LessonView[]): string[] {
  return lessons.map((l) => `${l.pair}:${l.discipline.shortName}:${l.subgroup}`);
}

describe('довідники з таблиці 2025/26', () => {
  it('містять усі 56 груп чотирьох курсів у канонічному форматі', () => {
    expect(GROUPS).toHaveLength(56);
    const perCourse = [1, 2, 3, 4].map((course) => GROUPS.filter((g) => g.course === course).length);
    expect(perCourse).toEqual([16, 15, 15, 10]);
    for (const group of GROUPS) expect(group.code).toMatch(/^\d[А-ЯІЇЄҐ]{1,3}\d?$/u);
    // «ІКІ1» і «1ТК 1» у таблиці — канонічні коди тут
    expect(findGroupByCode('1КІ1')).toBeDefined();
    expect(findGroupByCode('1ТК1')).toBeDefined();
  });

  it('мають обсяг, описаний у src/mocks/README.md', () => {
    expect([TEACHERS.length, DISCIPLINES.length, ROOMS.length, TEMPLATE_LESSONS.length]).toEqual([107, 128, 45, 1077]);
  });

  it('мають домашню аудиторію, куратора й зміну групи', () => {
    const group = groupNamed('4КІ1');
    expect(group.homeRoom?.name).toBe('308');
    expect(group.curator?.shortName).toBe('Голєва Т.В.');
    expect(group.shift).toBe(1);
    expect(groupNamed('2КІ1').shift).toBe(2);
    expect(group.specialty?.label).toBe('Компʼютерна інженерія');
  });

  it('кожна група належить відділенню з DOMAIN §12', () => {
    expect(DEPARTMENTS).toHaveLength(5);
    for (const group of GROUPS) {
      const specialty = SPECIALTIES.find((s) => s.id === group.specialty?.id);
      expect(specialty, group.code).toBeDefined();
      expect(DEPARTMENTS.some((d) => d.id === specialty?.departmentId)).toBe(true);
    }
  });

  it('розрізняють однофамільців і виправляють одруківки DOMAIN §10', () => {
    const names = TEACHERS.map((t) => t.shortName);
    expect(names).toContain('Гарболінська');
    expect(names).not.toContain('Граболінська');
    expect(names).not.toContain('Герасменко');
    const polishchuk = TEACHERS.filter((t) => t.lastName === 'Поліщук').map((t) => t.displayName);
    expect(new Set(polishchuk).size).toBe(polishchuk.length);
    expect(polishchuk.length).toBeGreaterThan(1);
    expect(teacherNamed('Кренцін')).toMatchObject({ displayName: 'Кренцін', linked: true });
  });

  it('дають повні назви лише відомим дисциплінам', () => {
    expect(DISCIPLINES.find((d) => d.shortName === 'СТП')).toMatchObject({
      name: 'Сучасні технології проєктування',
      needsReview: false,
    });
    expect(DISCIPLINES.find((d) => d.shortName === 'ДК')).toMatchObject({ name: 'ДК', needsReview: true });
  });

  it('позначають спортзал як приміщення на кілька груп', () => {
    expect(roomNamed('с.з.')).toMatchObject({ type: 'GYM', maxParallel: 3 });
    expect(roomNamed('14')).toMatchObject({ type: 'CLASSROOM', maxParallel: 1, capacity: null });
  });

  it('мають стабільні унікальні UUID', () => {
    const ids = [...GROUPS, ...TEACHERS, ...DISCIPLINES, ...ROOMS, ...TEMPLATE_LESSONS].map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id).toMatch(UUID_RE);
    expect(mockId('group', '4КІ1')).toBe(groupNamed('4КІ1').id);
    expect(mockId('group', '4КІ1')).not.toBe(mockId('group', '4КІ2'));
  });
});

describe('календар', () => {
  it('рахує парність як у розпорядженні №35 (19.10.2026 — 8 тиждень, знаменник)', () => {
    expect(semesterWeekNumber('2026-09-01')).toBe(1);
    expect(semesterWeekNumber('2026-10-19')).toBe(8);
    expect(parityOn('2026-10-19')).toBe('DENOMINATOR');
    expect(parityOn('2026-11-09')).toBe('NUMERATOR');
    expect(semesterWeeks()[0]).toEqual({ weekStart: '2026-08-31', weekNumber: 1, parity: 'NUMERATOR', overridden: false });
  });

  it('вважає навчальними лише пн–пт у межах семестру', () => {
    expect(isStudyDay('2026-11-09')).toBe(true);
    expect(isStudyDay('2026-11-07')).toBe(false);
    expect(isStudyDay('2026-08-31')).toBe(false);
    expect(isStudyDay('2026-12-31')).toBe(false);
  });

  it('кожне заняття шаблону має дзвінок і лежить у вікні зміни своєї групи', () => {
    for (const lesson of TEMPLATE_LESSONS) {
      expect(bellSlots(lesson.weekday).some((s) => s.pair === lesson.pair), lesson.id).toBe(true);
      const group = groupNamed(lesson.group.code);
      const window = SHIFT_WINDOWS.find((w) => w.shift === group.shift && w.weekday === lesson.weekday);
      expect(window, `${lesson.group.code} ${lesson.weekday}/${lesson.pair}`).toBeDefined();
      expect(lesson.pair).toBeGreaterThanOrEqual(window?.firstPair ?? 0);
      expect(lesson.pair).toBeLessThanOrEqual(window?.lastPair ?? 0);
    }
  });

  it('містить періоди з приміток розпорядження №35', () => {
    expect(CALENDAR_PERIODS.map((p) => p.kind)).toEqual(['PRACTICE', 'STUDY_MODE', 'STUDY_MODE']);
  });
});

describe('генератор розкладу', () => {
  const group = groupNamed('4КІ1');

  it('матеріалізує реальний розклад 4КІ1 на тиждень чисельника', () => {
    const schedule = buildSchedule('GROUP', group.id, '2026-11-09', '2026-11-15');
    expect(schedule?.owner).toEqual({ type: 'GROUP', id: group.id, label: '4КІ1' });
    expect(schedule?.days.map((d) => d.weekdayLabel)).toEqual(['ПОНЕДІЛОК', 'ВІВТОРОК', 'СЕРЕДА', 'ЧЕТВЕР', 'ПʼЯТНИЦЯ']);
    const [monday, , wednesday, thursday, friday] = schedule?.days ?? [];
    expect(pairsOf(monday?.lessons ?? [])).toEqual(['1:КМ:0', '2:СТП:0', '3:ДК:0', '4:ФВ:0']);
    expect(monday?.lessons[0]).toMatchObject({ startsAt: '08:30', endsAt: '09:30', kind: 'REGULAR', online: false });
    // DOMAIN §5: у середу чисельника є ще перша пара ДК
    expect(pairsOf(wednesday?.lessons ?? [])[0]).toBe('1:ДК:0');
    // у четвер 4 пара — дві підгрупи іноземної мови
    expect(thursday?.lessons.filter((l) => l.pair === 4).map((l) => l.subgroup)).toEqual([1, 2]);
    expect(friday?.lessons.map((l) => l.discipline.shortName)).toEqual(['СП', 'КБ', 'НДЕКСМ']);
  });

  it('у знаменник прибирає заняття лише чисельника', () => {
    const schedule = buildSchedule('GROUP', group.id, '2026-11-18', '2026-11-18');
    expect(parityOn('2026-11-18')).toBe('DENOMINATOR');
    expect(schedule?.days[0]?.lessons.some((l) => l.pair === 1)).toBe(false);
  });

  it('позначає дистанційне навчання 4КІ до 31.10 і прибирає пари групи на практиці', () => {
    const online = buildSchedule('GROUP', group.id, '2026-10-19', '2026-10-19')?.days[0];
    expect(online?.lessons.length).toBeGreaterThan(0);
    expect(online?.lessons.every((l) => l.online)).toBe(true);
    expect(online?.note).toBe('Дистанційне навчання груп 4КІ');
    expect(buildSchedule('GROUP', group.id, '2026-11-02', '2026-11-02')?.days[0]?.lessons.some((l) => l.online)).toBe(false);

    const practice = buildSchedule('GROUP', groupNamed('2КІ3').id, '2026-10-20', '2026-10-20')?.days[0];
    expect(practice).toMatchObject({ studyDay: true, lessons: [], note: 'Електрорадіомонтажна практика' });
  });

  it('збирає розклад викладача й аудиторії з усіх груп', () => {
    const krentsin = teacherNamed('Кренцін');
    const week = buildSchedule('TEACHER', krentsin.id, '2026-11-09', '2026-11-13');
    const lessons = week?.days.flatMap((d) => d.lessons) ?? [];
    expect(lessons.length).toBeGreaterThan(0);
    expect(lessons.every((l) => l.teacher?.id === krentsin.id)).toBe(true);
    expect(new Set(lessons.map((l) => l.group.code))).toContain('4КІ1');

    const room = roomNamed('14');
    const roomWeek = buildSchedule('ROOM', room.id, '2026-11-09', '2026-11-13');
    expect(roomWeek?.days.flatMap((d) => d.lessons).every((l) => l.room?.id === room.id)).toBe(true);
  });

  it('детермінований, пропускає вихідні й не має пар поза семестром', () => {
    const a = buildSchedule('GROUP', group.id, '2026-12-28', '2027-01-03');
    expect(a).toEqual(buildSchedule('GROUP', group.id, '2026-12-28', '2027-01-03'));
    expect(a?.days.map((d) => [d.date, d.studyDay, d.lessons.length > 0])).toEqual([
      ['2026-12-28', true, true],
      ['2026-12-29', true, true],
      ['2026-12-30', true, true],
      ['2026-12-31', false, false],
      ['2027-01-01', false, false],
    ]);
  });

  it('повертає null для невідомого власника і пояснює неправильні дати', () => {
    expect(buildSchedule('GROUP', mockId('group', 'немає'), '2026-11-09', '2026-11-09')).toBeNull();
    expect(scheduleRangeError('2026-11-09', '2026-11-13')).toBeNull();
    expect(scheduleRangeError(null, '2026-11-13')).toMatch(/from і to/);
    expect(scheduleRangeError('2026-11-13', '2026-11-09')).toMatch(/пізніше/);
    expect(scheduleRangeError('09.11.2026', '2026-11-13')).toMatch(/РРРР-ММ-ДД/);
    // 01.11–01.12 — рівно 31 день (можна), 01.11–02.12 — 32 (не можна)
    expect(scheduleRangeError('2026-11-01', '2026-12-01')).toBeNull();
    expect(scheduleRangeError('2026-11-01', '2026-12-02')).toMatch(String(MAX_SCHEDULE_DAYS));
  });

  it('накладає зміни на всі заняття дати, тож заміну видно обом викладачам', () => {
    const krentsin = teacherNamed('Кренцін');
    const nepyivoda = teacherNamed('Непийвода');
    const date = '2026-11-09';
    const target = lessonsOn(date).find((l) => l.group.code === '4КІ1' && l.pair === 2);
    expect(target?.teacher?.id).toBe(krentsin.id);
    const substitute = { id: nepyivoda.id, shortName: nepyivoda.shortName, displayName: nepyivoda.displayName };
    const overlay = (_: string, lessons: LessonView[]): LessonView[] =>
      lessons.map(
        (l): LessonView =>
          l.id === target?.id
            ? { ...l, kind: 'SUBSTITUTED', teacher: substitute, original: { discipline: null, teacher: l.teacher, room: null, pair: null }, reasonText: 'Зв.зам.' }
            : l,
      );
    const forKrentsin = buildSchedule('TEACHER', krentsin.id, date, date, { overlay })?.days[0]?.lessons ?? [];
    const forNepyivoda = buildSchedule('TEACHER', nepyivoda.id, date, date, { overlay })?.days[0]?.lessons ?? [];
    expect(forKrentsin.find((l) => l.id === target?.id)?.kind).toBe('SUBSTITUTED');
    expect(forNepyivoda.find((l) => l.id === target?.id)?.teacher?.id).toBe(nepyivoda.id);
  });
});
