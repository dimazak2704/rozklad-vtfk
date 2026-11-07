/**
 * Календар мок-API (DOMAIN §2, §12): навчальний рік, семестри, дзвінки, вікна змін, парність тижнів,
 * календарні періоди з приміток реальних розпоряджень.
 */
import type {
  BellProfileDto,
  BellProfileSlotDto,
  BellSlotDto,
  CalendarPeriodDto,
  GroupDto,
  IsoDate,
  SemesterDto,
  SemesterWeekDto,
  ShiftWindowDto,
  Uuid,
  WeekParity,
  Weekday,
} from '@/api/types';
import { addDays, compareIsoDates, diffDays, isoWeekday, startOfWeek } from '@/lib/date';
import { mockId } from '../ids';
import { groupNamed } from './catalog';

// ───────────────────────── Дзвінки ─────────────────────────

type SlotTimes = ReadonlyArray<readonly [startsAt: string, endsAt: string]>;

const MON_THU: readonly Weekday[] = [1, 2, 3, 4];
const ALL_DAYS: readonly Weekday[] = [1, 2, 3, 4, 5];

function slots(days: readonly Weekday[], times: SlotTimes): BellProfileSlotDto[] {
  return days.flatMap((weekday) =>
    times.map(([startsAt, endsAt], index) => ({ weekday, pair: index + 1, startsAt, endsAt })),
  );
}

function profile(name: string, active: boolean, profileSlots: BellProfileSlotDto[]): BellProfileDto {
  return { id: mockId('bell-profile', name), name, active, slots: profileSlots };
}

/** Профілі дзвінків з аркушів «дзвінки (2 зм)», «дзвінки» і сайту коледжу (DOMAIN §2). */
export const BELL_PROFILES: readonly BellProfileDto[] = [
  profile('2 зміни, 60 хв', true, [
    ...slots(MON_THU, [
      ['08:30', '09:30'],
      ['09:40', '10:40'],
      ['11:00', '12:00'],
      ['12:10', '13:10'],
      ['13:30', '14:30'],
      ['14:40', '15:40'],
      ['16:00', '17:00'],
      ['17:10', '18:10'],
    ]),
    ...slots(
      [5],
      [
        ['08:30', '09:30'],
        ['09:40', '10:40'],
        ['11:00', '12:00'],
        ['12:20', '13:20'],
        ['13:30', '14:30'],
        ['14:50', '15:50'],
      ],
    ),
  ]),
  profile('2 зміни, 50 хв', false, [
    ...slots(MON_THU, [
      ['08:30', '09:20'],
      ['09:30', '10:20'],
      ['10:40', '11:30'],
      ['11:40', '12:30'],
      ['12:50', '13:40'],
      ['13:50', '14:40'],
      ['15:00', '15:50'],
      ['16:00', '16:50'],
    ]),
    ...slots(
      [5],
      [
        ['08:30', '09:20'],
        ['09:30', '10:20'],
        ['10:40', '11:30'],
        ['11:50', '12:40'],
        ['12:50', '13:40'],
        ['14:00', '14:50'],
      ],
    ),
  ]),
  profile(
    '1 зміна, 80 хв',
    false,
    slots(ALL_DAYS, [
      ['08:30', '09:50'],
      ['10:00', '11:20'],
      ['12:00', '13:20'],
      ['13:30', '14:50'],
    ]),
  ),
];

export const ACTIVE_BELL_PROFILE: BellProfileDto = BELL_PROFILES.find((p) => p.active) as BellProfileDto;

/** Дзвінки профілю на день тижня (без поля weekday — як у GET /api/public/bells). */
export function bellSlots(weekday: Weekday, bellProfile: BellProfileDto = ACTIVE_BELL_PROFILE): BellSlotDto[] {
  return bellProfile.slots
    .filter((slot) => slot.weekday === weekday)
    .map(({ pair, startsAt, endsAt }) => ({ pair, startsAt, endsAt }));
}

/** Вікна змін (DOMAIN §2): 1 зміна — пари 1–4; 2 зміна — 5–8 пн–чт і 4–6 у пʼятницю. */
export const SHIFT_WINDOWS: readonly ShiftWindowDto[] = [
  ...ALL_DAYS.map((weekday) => ({ shift: 1 as const, weekday, firstPair: 1, lastPair: 4 })),
  ...MON_THU.map((weekday) => ({ shift: 2 as const, weekday, firstPair: 5, lastPair: 8 })),
  { shift: 2, weekday: 5, firstPair: 4, lastPair: 6 },
];

// ───────────────────────── Семестри ─────────────────────────

export const ACADEMIC_YEAR = {
  id: mockId('academic-year', '2026/2027'),
  name: '2026/2027',
  startsOn: '2026-09-01',
  endsOn: '2027-06-30',
} as const;

function weeksBetween(startsOn: IsoDate, endsOn: IsoDate): number {
  return Math.floor(diffDays(startOfWeek(startsOn), startOfWeek(endsOn)) / 7) + 1;
}

/** Демо-семестри (DOMAIN §12): I — активний, II — порожній. */
export const SEMESTERS: readonly SemesterDto[] = [
  {
    id: mockId('semester', '2026/2027-1'),
    academicYearId: ACADEMIC_YEAR.id,
    number: 1,
    name: 'I семестр 2026/2027',
    startsOn: '2026-09-01',
    endsOn: '2026-12-30',
    firstWeekParity: 'NUMERATOR',
    weeksCount: weeksBetween('2026-09-01', '2026-12-30'),
    bellProfileId: ACTIVE_BELL_PROFILE.id,
    active: true,
  },
  {
    id: mockId('semester', '2026/2027-2'),
    academicYearId: ACADEMIC_YEAR.id,
    number: 2,
    name: 'II семестр 2026/2027',
    startsOn: '2027-01-19',
    endsOn: '2027-06-30',
    firstWeekParity: 'NUMERATOR',
    weeksCount: weeksBetween('2027-01-19', '2027-06-30'),
    bellProfileId: ACTIVE_BELL_PROFILE.id,
    active: false,
  },
];

export const ACTIVE_SEMESTER: SemesterDto = SEMESTERS.find((s) => s.active) as SemesterDto;

/** Ручні перевизначення парності тижня (`week_parity_override`); у демо їх немає. */
export const PARITY_OVERRIDES: ReadonlyArray<{ semesterId: Uuid; weekStart: IsoDate; parity: WeekParity }> = [];

const OTHER_PARITY: Record<WeekParity, WeekParity> = { NUMERATOR: 'DENOMINATOR', DENOMINATOR: 'NUMERATOR' };

/** Номер тижня: тиждень 1 — той, що містить дату початку семестру (понеділок — перший день). */
export function semesterWeekNumber(date: IsoDate, semester: SemesterDto = ACTIVE_SEMESTER): number {
  return Math.floor(diffDays(startOfWeek(semester.startsOn), startOfWeek(date)) / 7) + 1;
}

/**
 * Чисельник/знаменник (DOMAIN §2): непарні тижні — `firstWeekParity`, парні — інша; перевизначення важливіше.
 * Перевірено: семестр з 01.09.2026 ⇒ тиждень 19.10.2026 восьмий ⇒ ЗНАМЕННИК (як у розпорядженні №35).
 */
export function parityOn(date: IsoDate, semester: SemesterDto = ACTIVE_SEMESTER): WeekParity {
  const weekStart = startOfWeek(date);
  const override = PARITY_OVERRIDES.find((o) => o.semesterId === semester.id && o.weekStart === weekStart);
  if (override) return override.parity;
  const week = semesterWeekNumber(date, semester);
  return Math.abs(week) % 2 === 1 ? semester.firstWeekParity : OTHER_PARITY[semester.firstWeekParity];
}

/** Тижні семестру з парністю (GET /api/admin/semesters/{id}/weeks). */
export function semesterWeeks(semester: SemesterDto = ACTIVE_SEMESTER): SemesterWeekDto[] {
  const first = startOfWeek(semester.startsOn);
  const count = weeksBetween(semester.startsOn, semester.endsOn);
  return Array.from({ length: count }, (_, index) => {
    const weekStart = addDays(first, index * 7);
    return {
      weekStart,
      weekNumber: index + 1,
      parity: parityOn(weekStart, semester),
      overridden: PARITY_OVERRIDES.some((o) => o.semesterId === semester.id && o.weekStart === weekStart),
    };
  });
}

export function isWithinSemester(date: IsoDate, semester: SemesterDto = ACTIVE_SEMESTER): boolean {
  return compareIsoDates(date, semester.startsOn) >= 0 && compareIsoDates(date, semester.endsOn) <= 0;
}

// ───────────────────────── Календарні періоди ─────────────────────────

const groupIdsOf = (...codes: string[]): Uuid[] => codes.map((code) => groupNamed(code).id);

/**
 * Періоди з приміток розпорядження №35 (DOMAIN §11–12). Осінні канікули не відомі — їх немає.
 */
export const CALENDAR_PERIODS: readonly CalendarPeriodDto[] = [
  {
    id: mockId('calendar-period', '2КІ3-practice-2026-10'),
    semesterId: ACTIVE_SEMESTER.id,
    kind: 'PRACTICE',
    title: 'Електрорадіомонтажна практика',
    startsOn: '2026-10-19',
    endsOn: '2026-10-30',
    studyMode: null,
    orderNote: '2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.',
    scope: 'GROUPS',
    courses: null,
    groupIds: groupIdsOf('2КІ3'),
  },
  {
    id: mockId('calendar-period', 'course-3-online-2026'),
    semesterId: ACTIVE_SEMESTER.id,
    kind: 'STUDY_MODE',
    title: 'Дистанційне навчання 3 курсу',
    startsOn: '2026-09-01',
    endsOn: '2026-10-31',
    studyMode: 'ONLINE',
    orderNote: null,
    scope: 'COURSES',
    courses: [3],
    groupIds: null,
  },
  {
    id: mockId('calendar-period', '4КІ-online-2026'),
    semesterId: ACTIVE_SEMESTER.id,
    kind: 'STUDY_MODE',
    title: 'Дистанційне навчання груп 4КІ',
    startsOn: '2026-09-01',
    endsOn: '2026-10-31',
    studyMode: 'ONLINE',
    orderNote: 'Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.',
    scope: 'GROUPS',
    courses: null,
    groupIds: groupIdsOf('4КІ1', '4КІ2', '4КІ3'),
  },
];

/** Чи стосується період групи (або всіх, якщо група не вказана). */
export function periodApplies(period: CalendarPeriodDto, group?: GroupDto): boolean {
  switch (period.scope) {
    case 'ALL':
      return true;
    case 'COURSES':
      return group !== undefined && (period.courses ?? []).includes(group.course);
    case 'GROUPS':
      return group !== undefined && (period.groupIds ?? []).includes(group.id);
  }
}

/** Періоди, що діють на дату (для групи — ще й групові та курсові). */
export function periodsOn(date: IsoDate, group?: GroupDto, periods: readonly CalendarPeriodDto[] = CALENDAR_PERIODS) {
  return periods.filter(
    (p) => compareIsoDates(date, p.startsOn) >= 0 && compareIsoDates(date, p.endsOn) <= 0 && periodApplies(p, group),
  );
}

/** Навчальний день: пн–пт, у межах семестру і не в загальні канікули чи «без занять». */
export function isStudyDay(date: IsoDate, semester: SemesterDto = ACTIVE_SEMESTER): boolean {
  if (isoWeekday(date) > 5 || !isWithinSemester(date, semester)) return false;
  return !periodsOn(date).some((p) => p.kind === 'HOLIDAY' || p.kind === 'NO_CLASSES');
}
