/**
 * Статичні відповіді рекомендатора v2 (CONTRACTS §2) для тестів компонентів: форма рівно як у core
 * (`OptionDto`, `LessonOptions`, `TeacherAvailability.Item`, `DayPlanDto`), без балів у інтерфейсі.
 * ДЕМО-дані за мотивами розпорядження №35 (1М, Ін.мова, Крисак Л. на лікарняному), не факт із документів.
 */
import type {
  DayPlanDto,
  LessonOptionsDto,
  LessonView,
  OptionDto,
  RoomRef,
  TeacherAvailabilityDto,
  TeacherRef,
} from '@/api/types';

const teacher = (id: string, shortName: string): TeacherRef => ({ id, shortName, displayName: shortName });
const room = (id: string, name: string): RoomRef => ({ id, name, type: 'CLASSROOM' });

export const FIXTURE_TEACHERS = {
  krysak: teacher('t-krysak', 'Крисак Л.'),
  mozharovska: teacher('t-mozharovska', 'Можаровська'),
  nepyivoda: teacher('t-nepyivoda', 'Непийвода'),
  stets: teacher('t-stets', 'Стець'),
  hanza: teacher('t-hanza', 'Ганза'),
  balun: teacher('t-balun', 'Балунь'),
} as const;

const ROOMS = { r110: room('r-110', '110'), r12: room('r-12', '12'), r6: room('r-6', '6'), r309: room('r-309', '309') } as const;

const lessonBase: LessonView = {
  id: 'fx-lesson-3',
  pair: 3,
  startsAt: '11:50',
  endsAt: '12:50',
  discipline: { id: 'd-inmova', shortName: 'Ін.мова', name: 'Іноземна мова', color: null },
  teacher: FIXTURE_TEACHERS.krysak,
  room: ROOMS.r110,
  group: { id: 'g-1m', code: '1М', course: 1 },
  subgroup: 1,
  kind: 'REGULAR',
  online: false,
  original: null,
  reasonText: null,
  changeId: null,
};

export const FIXTURE_LESSON = lessonBase;
export const FIXTURE_PARTNER: LessonView = { ...lessonBase, id: 'fx-lesson-3-sg2', subgroup: 2, teacher: FIXTURE_TEACHERS.mozharovska, room: ROOMS.r12 };
const later: LessonView = {
  ...lessonBase,
  id: 'fx-lesson-5',
  pair: 5,
  discipline: { id: 'd-ukrlit', shortName: 'Укр.літ.', name: 'Українська література', color: null },
  teacher: FIXTURE_TEACHERS.balun,
};

const option = (patch: Partial<OptionDto> & Pick<OptionDto, 'kind' | 'title'>): OptionDto => ({
  score: 0,
  reasons: [],
  teacher: null,
  discipline: null,
  room: null,
  swapLesson: null,
  warnings: [],
  date: null,
  pair: null,
  band: 'CLEAN',
  recommended: false,
  suggestedRoom: null,
  ...patch,
});

/** П'ять варіантів у порядку сервера: перший рекомендовано, обʼєднання, ⚠-варіант, перестановка, скасування. */
const FIVE: OptionDto[] = [
  option({
    kind: 'REPLACE_DISCIPLINE',
    score: 100,
    title: 'НДКС — Непийвода',
    reasons: ['НДКС відстає від плану', 'Закриває вікно в розкладі викладача', 'Сьогодні має 2, 4 пари'],
    teacher: FIXTURE_TEACHERS.nepyivoda,
    discipline: { id: 'd-ndks', shortName: 'НДКС', name: 'НДКС', color: null },
    room: ROOMS.r110,
    suggestedRoom: ROOMS.r6,
    recommended: true,
  }),
  option({
    kind: 'MERGE_SUBGROUPS',
    score: 90,
    title: "Об'єднати підгрупи: Ін.мова — Можаровська",
    reasons: ['Можаровська уже веде Ін.мова для 2 підгрупи — візьме обидві'],
    teacher: FIXTURE_TEACHERS.mozharovska,
    discipline: lessonBase.discipline,
    room: ROOMS.r12,
    swapLesson: FIXTURE_PARTNER,
    suggestedRoom: ROOMS.r12,
  }),
  option({
    kind: 'REPLACE_DISCIPLINE',
    score: 80,
    title: 'Прогр. — Стець',
    reasons: ['Сьогодні має 1 пару'],
    warnings: ['Вже має пару з цією групою (1 пара) — краще перестановка', 'Прогр. піде понад план'],
    teacher: FIXTURE_TEACHERS.stets,
    discipline: { id: 'd-prog', shortName: 'Прогр.', name: 'Програмування', color: null },
    suggestedRoom: ROOMS.r309,
    band: 'CAUTION',
  }),
  option({
    kind: 'SWAP',
    score: 70,
    title: 'Переставити 5 пару (Укр.літ.) на 3',
    reasons: ['Група піде раніше'],
    teacher: FIXTURE_TEACHERS.balun,
    discipline: later.discipline,
    swapLesson: later,
  }),
  option({ kind: 'CANCEL', score: 60, title: 'Пари не буде', warnings: ["У групи з'явиться вікно"], band: 'LAST' }),
];

export const OPTIONS_RECOMMENDED: LessonOptionsDto = {
  lesson: lessonBase,
  options: FIVE,
  declined: [{ teacher: FIXTURE_TEACHERS.hanza, at: '2026-11-09T12:05:00Z' }],
  past: false,
};

/** Те саме без рекомендації: сервер не бачить явно кращого. */
export const OPTIONS_EQUAL: LessonOptionsDto = {
  lesson: lessonBase,
  options: FIVE.map((o) => ({ ...o, recommended: false })),
  declined: [],
  past: false,
};

/** У групи нікого вільного: лише перестановка й скасування. */
export const OPTIONS_EMPTY_POOL: LessonOptionsDto = {
  lesson: lessonBase,
  options: FIVE.filter((o) => o.kind === 'SWAP' || o.kind === 'CANCEL').map((o, index) => ({ ...o, score: 100 - 10 * index })),
  declined: [],
  past: false,
};

export const OPTIONS_PAST: LessonOptionsDto = { ...OPTIONS_EQUAL, past: true };

export const TEACHER_AVAILABILITY: TeacherAvailabilityDto[] = [
  { teacher: FIXTURE_TEACHERS.balun, status: 'FREE', detail: null },
  { teacher: teacher('t-prokopchuk', 'Прокопчук'), status: 'FREE', detail: null },
  { teacher: FIXTURE_TEACHERS.hanza, status: 'DECLINED', detail: '14:05' },
  { teacher: FIXTURE_TEACHERS.stets, status: 'BUSY', detail: '3 пара, 4КІ2' },
  { teacher: teacher('t-ohota', 'Охота О.'), status: 'ABSENT', detail: null },
  { teacher: teacher('t-yatsuba', 'Яцуба'), status: 'UNAVAILABLE', detail: null },
];

/** План дня Крисак Л.: дві пари, обидві з пропозицією. */
export const DAY_PLAN: DayPlanDto = {
  date: '2026-11-09',
  lessons: [
    { lesson: lessonBase, options: FIVE, proposedIndex: 0, declined: [], past: false },
    {
      lesson: { ...lessonBase, id: 'fx-lesson-4', pair: 4, subgroup: 0 },
      options: [
        option({
          kind: 'REPLACE_DISCIPLINE',
          score: 100,
          title: 'Укр.літ. — Балунь',
          reasons: ['Сьогодні має 5 пару'],
          teacher: FIXTURE_TEACHERS.balun,
          discipline: later.discipline,
          suggestedRoom: ROOMS.r309,
          recommended: true,
        }),
        option({ kind: 'CANCEL', score: 90, title: 'Пари не буде', reasons: ['Остання пара: група піде раніше'], band: 'EASY_CANCEL' }),
      ],
      proposedIndex: 0,
      declined: [],
      past: false,
    },
  ],
};
