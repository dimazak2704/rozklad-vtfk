import { describe, expect, it } from 'vitest';
import type { DayDto, LessonView, ScheduleDto } from '@/api/types';
import { bellColumns, breakAfter } from '../bells/bells-columns';
import { coursesIn, filterChangeGroups, myTargets, parseCourses, sameTeacher, type ChangeGroup } from '../changes/changes-filter';
import { parseSubgroup, parseView, resolveScheduleDate, studyWeek } from './schedule-params';
import {
  activePairs,
  changeSummary,
  changedLessons,
  dayShareText,
  filterBySubgroup,
  reasonHint,
  roomDetails,
  roomOccupancy,
  semesterWeekOf,
  subgroupsIn,
} from './schedule-utils';

const ref = (id: string) => ({ id, shortName: id, name: `${id} повна`, color: null });
const teacher = (name: string) => ({ id: name, shortName: name, displayName: name });

function lesson(overrides: Partial<LessonView>): LessonView {
  return {
    id: crypto.randomUUID(),
    pair: 1,
    startsAt: '08:30',
    endsAt: '09:30',
    discipline: ref('КМ'),
    teacher: teacher('Колесник'),
    room: { id: 'r14', name: '14', type: 'CLASSROOM' },
    group: { id: 'g', code: '4КІ1', course: 4 },
    subgroup: 0,
    kind: 'REGULAR',
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
    ...overrides,
  };
}

function day(date: string, lessons: LessonView[], extra: Partial<DayDto> = {}): DayDto {
  return { date, weekday: 1, weekdayLabel: 'ПОНЕДІЛОК', parity: 'DENOMINATOR', studyDay: true, note: null, lessons, ...extra };
}

describe('параметри сторінки розкладу', () => {
  it('розбирає подання й підгрупу, ігнорує сміття', () => {
    expect(parseView('week')).toBe('week');
    expect(parseView('month')).toBeNull();
    expect(parseSubgroup('2')).toBe(2);
    expect(parseSubgroup('0')).toBeNull();
    expect(parseSubgroup('12')).toBeNull();
  });

  it('вихідний переходить на понеділок, тиждень — пн–пт', () => {
    expect(resolveScheduleDate('2026-11-14', '2026-11-09')).toBe('2026-11-16');
    expect(resolveScheduleDate('погано', '2026-11-08')).toBe('2026-11-09');
    expect(studyWeek('2026-11-12')).toEqual({ from: '2026-11-09', to: '2026-11-13' });
  });
});

describe('логіка розкладу', () => {
  const substituted = lesson({
    pair: 2,
    kind: 'SUBSTITUTED',
    discipline: ref('НДЕКСМ'),
    teacher: teacher('Непийвода'),
    original: { discipline: ref('СТП'), teacher: teacher('Кренцін'), room: null, pair: null },
    reasonText: 'Зв.зам.',
  });
  const schedule: ScheduleDto = {
    owner: { type: 'GROUP', id: 'g', label: '4КІ1' },
    from: '2026-10-19',
    to: '2026-10-19',
    days: [
      day('2026-10-19', [
        lesson({ pair: 1 }),
        substituted,
        lesson({ pair: 3, subgroup: 1, discipline: ref('Ін.мова') }),
        lesson({ pair: 3, subgroup: 2, discipline: ref('Ін.мова') }),
        lesson({ pair: 4, kind: 'CANCELLED', reasonText: 'Відп.' }),
      ]),
    ],
  };

  it('підгрупи й фільтр лишають пари всієї групи', () => {
    expect(subgroupsIn(schedule)).toEqual([1, 2]);
    const first = filterBySubgroup(schedule, 1).days[0]?.lessons ?? [];
    expect(first.filter((l) => l.pair === 3).map((l) => l.subgroup)).toEqual([1]);
    expect(first).toHaveLength(4);
  });

  it('описує зміну і пропускає скасовані пари в «зараз»', () => {
    expect(changeSummary(substituted)).toBe('НДЕКСМ, Непийвода замість СТП, Кренцін');
    expect(changedLessons(schedule).map((c) => c.lesson.pair)).toEqual([2, 4]);
    expect(activePairs(schedule.days[0])).toEqual([1, 2, 3]);
    expect(reasonHint('Зв.зам.')).toBe('Взаємна заміна');
  });

  it('текст «Поділитися» містить день, парність, пари й зміни', () => {
    const text = dayShareText('4КІ1', schedule.days[0], 'group');
    expect(text.split('\n')[0]).toBe('4КІ1 — понеділок, 19 жовтня (знаменник)');
    expect(text).toContain('2 пара, 08:30 — НДЕКСМ, Непийвода, ауд. 14 — НДЕКСМ, Непийвода замість СТП, Кренцін (Зв.зам.)');
    expect(text).toContain('4 пара, 08:30 — КМ, Колесник, ауд. 14 — пари не буде (Відп.)');
    expect(dayShareText('4КІ1', day('2026-10-24', [], { studyDay: false }), 'group')).toContain('не навчальний день');
  });

  it('зайнятість аудиторії не рахує скасованих пар', () => {
    const occupancy = roomOccupancy(schedule, 'r14', () => 8);
    expect(occupancy.total).toBe(8);
    // 1, 2 (НДЕКСМ), 3 (дві підгрупи — одна пара); 4 скасована
    expect(occupancy.busy).toBe(3);
  });

  it('номер тижня семестру — від тижня з датою початку', () => {
    const semester = { startsOn: '2026-09-01', endsOn: '2026-12-30' };
    expect(semesterWeekOf('2026-10-19', semester)).toBe(8);
    expect(semesterWeekOf('2026-08-31', semester)).toBe(1);
    expect(semesterWeekOf('2027-01-04', semester)).toBeNull();
  });
});

describe('фільтр замін', () => {
  const groups: ChangeGroup[] = [
    {
      group: { id: 'a', code: '1КІ2', course: 1 },
      items: [
        {
          pairLabel: '2',
          rowType: 'REPLACEMENT',
          from: { discipline: 'Ін.мова', teacher: 'Крисак Л.' },
          reasonText: 'Відп.',
          to: { discipline: 'Ін.мова', teacher: 'Прокопчук' },
          room: '110',
          subgroupNote: null,
        },
      ],
    },
    {
      group: { id: 'b', code: '4КІ1', course: 4 },
      items: [
        {
          pairLabel: '2',
          rowType: 'REPLACEMENT',
          from: { discipline: 'СТП', teacher: 'Кренцін' },
          reasonText: 'Зв.зам.',
          to: { discipline: 'НДЕКСМ', teacher: 'Непийвода' },
          room: '6',
          subgroupNote: null,
        },
      ],
    },
  ];

  it('однофамільців розрізняє ініціалами', () => {
    expect(sameTeacher('Крисак Л.', 'Крисак Л.В.')).toBe(true);
    expect(sameTeacher('Крисак Л.', 'Крисак А.О.')).toBe(false);
    expect(sameTeacher('Кренцін', 'Кренцін М.Д.')).toBe(true);
  });

  it('за групою латиницею, курсом і «лише мої» (група або викладач з обраного)', () => {
    const none = myTargets([]);
    expect(filterChangeGroups(groups, { query: '4ki1', courses: [], mineOnly: false }, none).map((g) => g.group.code)).toEqual(['4КІ1']);
    expect(filterChangeGroups(groups, { query: '', courses: [1], mineOnly: false }, none).map((g) => g.group.code)).toEqual(['1КІ2']);
    const mine = myTargets([{ type: 'TEACHER', id: 't', label: 'Крисак Л.В.', url: '/t/t' }]);
    expect(filterChangeGroups(groups, { query: '', courses: [], mineOnly: true }, mine).map((g) => g.group.code)).toEqual(['1КІ2']);
    expect(coursesIn(groups)).toEqual([1, 4]);
    expect(parseCourses('4,1,x,9,1')).toEqual([1, 4]);
  });
});

describe('колонки дзвінків', () => {
  it('обʼєднує дні з однаковими дзвінками й рахує перерви', () => {
    const slot = (weekday: 1 | 2 | 3 | 4 | 5, pair: number, startsAt: string, endsAt: string) => ({ weekday, pair, startsAt, endsAt });
    const columns = bellColumns({
      id: 'p',
      name: 'Тест',
      active: true,
      defaultProfile: true,
      version: 0,
      slots: [
        ...([1, 2, 3, 4] as const).flatMap((d) => [slot(d, 1, '08:30', '09:30'), slot(d, 2, '09:40', '10:40')]),
        slot(5, 1, '08:30', '09:30'),
        slot(5, 2, '09:50', '10:50'),
      ],
    });
    expect(columns.map((c) => c.label)).toEqual(['Понеділок – четвер', 'Пʼятниця']);
    expect(columns.map((c) => c.short)).toEqual(['Пн–Чт', 'Пт']);
    expect(breakAfter(columns[1]?.slots ?? [], 1)).toBe(20);
    expect(breakAfter(columns[1]?.slots ?? [], 2)).toBeNull();
  });
});

describe('roomDetails', () => {
  it('для спортзалу каже, скільки груп приймає одночасно; звичайну аудиторію не обтяжує', () => {
    expect(roomDetails({ id: 'g', name: 'с.з.', type: 'GYM', capacity: null, building: null, maxParallel: 3 })).toBe(
      'Спортзал · до 3 груп одночасно',
    );
    expect(roomDetails({ id: 'r', name: '6', type: 'COMPUTER_LAB', capacity: 30, building: '2', maxParallel: 1 })).toBe(
      'Компʼютерний клас · 30 місць · корпус: 2',
    );
  });
});
