/**
 * Демонстраційні дані для сторінки компонентів (лише dev). Розклад 4КІ1 узято з seed/schedule-2025-26.xlsx
 * (I семестр), дати перенесено на тиждень 9–13.11.2026 (чисельник, 11 тиждень). Зміни — синтетичні,
 * за мотивами розпорядження №35 (seed/orders/order-035-2026-10-19.jpg), і позначені як демо.
 */
import type {
  BellSlotDto,
  DayDto,
  DisciplineRef,
  LessonView,
  LessonViewKind,
  OptionDto,
  RoomRef,
  ScheduleDto,
  TeacherRef,
} from '@/api/types';

const d = (id: string, shortName: string, name: string): DisciplineRef => ({ id, shortName, name, color: null });
const t = (id: string, shortName: string, displayName: string): TeacherRef => ({ id, shortName, displayName });
const r = (id: string, name: string, type: RoomRef['type'] = 'CLASSROOM'): RoomRef => ({ id, name, type });

export const DISCIPLINES = {
  km: d('disc-km', 'КМ', 'Компʼютерні мережі'),
  stp: d('disc-stp', 'СТП', 'Сучасні технології проєктування'),
  dk: d('disc-dk', 'ДК', 'ДК'),
  fv: d('disc-fv', 'ФВ', 'Фізичне виховання'),
  prog: d('disc-prog', 'Прогр.', 'Прогр.'),
  eu: d('disc-eu', 'ЕУ', 'Економіка та управління ІТ-проєктами'),
  opcz: d('disc-opcz', 'ОПЦЗ', 'Охорона праці та цивільний захист'),
  ukr: d('disc-ukr', 'Укр.мов.', 'Українська мова (за професійним спрямуванням)'),
  eng: d('disc-eng', 'Ін.мова', 'Іноземна мова'),
  sp: d('disc-sp', 'СП', 'СП'),
  kb: d('disc-kb', 'КБ', 'Кібербезпека'),
  ndeksm: d('disc-ndeksm', 'НДЕКСМ', 'Надійність, діагностика та експлуатація компʼютерних систем і мереж'),
  zu: d('disc-zu', 'ЗУ', 'Захист України'),
};

export const TEACHERS = {
  kolesnyk: t('t-kolesnyk', 'Колесник А.І.', 'Колесник А.'),
  krentsin: t('t-krentsin', 'Кренцін М.Д.', 'Кренцін'),
  palamarchuk: t('t-palamarchuk', 'Паламарчук', 'Паламарчук'),
  burbela: t('t-burbela', 'Бурбела', 'Бурбела'),
  stets: t('t-stets', 'Стець', 'Стець'),
  aleksieienko: t('t-aleksieienko', 'Алексєєнко О.А.', 'Алексєєнко'),
  hanza: t('t-hanza', 'Ганза', 'Ганза'),
  zorina: t('t-zorina', 'Зоріна', 'Зоріна'),
  prokopchuk: t('t-prokopchuk', 'Прокопчук', 'Прокопчук'),
  harbolinska: t('t-harbolinska', 'Гарболінська', 'Гарболінська'),
  ordynskyi: t('t-ordynskyi', 'Ординський', 'Ординський'),
  nepyivoda: t('t-nepyivoda', 'Непийвода М.В.', 'Непийвода'),
  yemelianenko: t('t-yemelianenko', 'Ємельяненко', 'Ємельяненко'),
};

const ROOMS = {
  r14: r('room-14', '14', 'COMPUTER_LAB'),
  r301: r('room-301', '301', 'COMPUTER_LAB'),
  r203: r('room-203', '203'),
  gym: r('room-gym', 'с.з.', 'GYM'),
  r11: r('room-11', '11'),
  r208: r('room-208', '208', 'COMPUTER_LAB'),
  r17: r('room-17', '17'),
  r309: r('room-309', '309'),
  r413: r('room-413', '413'),
  r16: r('room-16', '16'),
  r9: r('room-9', '9'),
  r6: r('room-6', '6', 'COMPUTER_LAB'),
  r204: r('room-204', '204'),
};

const GROUP = { id: 'group-4ki1', code: '4КІ1', course: 4 };

/** «2 зміни, 60 хв» (DOMAIN §2) */
export const BELLS_MON_THU: BellSlotDto[] = [
  { pair: 1, startsAt: '08:30', endsAt: '09:30' },
  { pair: 2, startsAt: '09:40', endsAt: '10:40' },
  { pair: 3, startsAt: '11:00', endsAt: '12:00' },
  { pair: 4, startsAt: '12:10', endsAt: '13:10' },
  { pair: 5, startsAt: '13:30', endsAt: '14:30' },
  { pair: 6, startsAt: '14:40', endsAt: '15:40' },
  { pair: 7, startsAt: '16:00', endsAt: '17:00' },
  { pair: 8, startsAt: '17:10', endsAt: '18:10' },
];

export const BELLS_FRI: BellSlotDto[] = [
  { pair: 1, startsAt: '08:30', endsAt: '09:30' },
  { pair: 2, startsAt: '09:40', endsAt: '10:40' },
  { pair: 3, startsAt: '11:00', endsAt: '12:00' },
  { pair: 4, startsAt: '12:20', endsAt: '13:20' },
  { pair: 5, startsAt: '13:30', endsAt: '14:30' },
  { pair: 6, startsAt: '14:50', endsAt: '15:50' },
];

interface LessonInput {
  pair: number;
  discipline: DisciplineRef;
  teacher: TeacherRef;
  room: RoomRef;
  subgroup?: number;
  kind?: LessonViewKind;
  original?: LessonView['original'];
  reasonText?: string;
  online?: boolean;
}

function day(date: string, weekday: DayDto['weekday'], weekdayLabel: string, lessons: LessonInput[], note?: string): DayDto {
  const bells = weekday === 5 ? BELLS_FRI : BELLS_MON_THU;
  return {
    date,
    weekday,
    weekdayLabel,
    parity: 'NUMERATOR',
    studyDay: true,
    note: note ?? null,
    lessons: lessons.map((input) => {
      const bell = bells.find((b) => b.pair === input.pair) ?? BELLS_MON_THU[0];
      return {
        id: `l-${date}-${input.pair}-${input.subgroup ?? 0}`,
        pair: input.pair,
        startsAt: bell?.startsAt ?? '08:30',
        endsAt: bell?.endsAt ?? '09:30',
        discipline: input.discipline,
        teacher: input.teacher,
        room: input.room,
        group: GROUP,
        subgroup: input.subgroup ?? 0,
        kind: input.kind ?? 'REGULAR',
        online: input.online ?? false,
        original: input.original ?? null,
        reasonText: input.reasonText ?? null,
        changeId: input.kind && input.kind !== 'REGULAR' ? `change-${date}-${input.pair}` : null,
      };
    }),
  };
}

const { km, stp, dk, fv, prog, eu, opcz, ukr, eng, sp, kb, ndeksm } = DISCIPLINES;
const T = TEACHERS;

export const DEMO_WEEK: ScheduleDto = {
  owner: { type: 'GROUP', id: GROUP.id, label: GROUP.code },
  from: '2026-11-09',
  to: '2026-11-13',
  days: [
    day('2026-11-09', 1, 'ПОНЕДІЛОК', [
      { pair: 1, discipline: km, teacher: T.kolesnyk, room: ROOMS.r14 },
      {
        pair: 2,
        discipline: ndeksm,
        teacher: T.nepyivoda,
        room: ROOMS.r6,
        kind: 'SUBSTITUTED',
        original: { discipline: stp, teacher: T.krentsin, room: ROOMS.r301, pair: null },
        reasonText: 'Зв.зам.',
      },
      { pair: 3, discipline: dk, teacher: T.palamarchuk, room: ROOMS.r203 },
      { pair: 4, discipline: fv, teacher: T.burbela, room: ROOMS.gym },
    ]),
    day('2026-11-10', 2, 'ВІВТОРОК', [
      { pair: 1, discipline: prog, teacher: T.stets, room: ROOMS.r301 },
      { pair: 2, discipline: eu, teacher: T.aleksieienko, room: ROOMS.r11 },
      { pair: 3, discipline: stp, teacher: T.krentsin, room: ROOMS.r208 },
      { pair: 4, discipline: opcz, teacher: T.hanza, room: ROOMS.r309, kind: 'ROOM_CHANGED', original: { discipline: null, teacher: null, room: ROOMS.r17, pair: null } },
    ]),
    day('2026-11-11', 3, 'СЕРЕДА', [
      { pair: 1, discipline: dk, teacher: T.palamarchuk, room: ROOMS.r203 },
      { pair: 2, discipline: prog, teacher: T.stets, room: ROOMS.r301 },
      { pair: 3, discipline: ukr, teacher: T.zorina, room: ROOMS.r413 },
      { pair: 4, discipline: stp, teacher: T.krentsin, room: ROOMS.r208, kind: 'ADDITIONAL', reasonText: 'Відпрацювання' },
    ]),
    day(
      '2026-11-12',
      4,
      'ЧЕТВЕР',
      [
        { pair: 2, discipline: eu, teacher: T.aleksieienko, room: ROOMS.r11 },
        { pair: 3, discipline: km, teacher: T.kolesnyk, room: ROOMS.r14, kind: 'CANCELLED', reasonText: 'Відп.' },
        { pair: 4, discipline: eng, teacher: T.prokopchuk, room: ROOMS.r16, subgroup: 1 },
        { pair: 4, discipline: eng, teacher: T.harbolinska, room: ROOMS.r9, subgroup: 2 },
      ],
      'Демо: 3 пару скасовано, у 4 пару — дві підгрупи в різних аудиторіях.',
    ),
    day('2026-11-13', 5, 'ПʼЯТНИЦЯ', [
      { pair: 1, discipline: sp, teacher: T.ordynskyi, room: ROOMS.r208 },
      { pair: 2, discipline: kb, teacher: T.nepyivoda, room: ROOMS.r14 },
      { pair: 3, discipline: ndeksm, teacher: T.nepyivoda, room: ROOMS.r14, kind: 'MOVED', original: { discipline: null, teacher: null, room: null, pair: 4 }, online: true },
    ]),
  ],
};

/** Блок пар (як «1ТК1 | 1-4 | ЗУ Ємельяненко | 204» у розпорядженні №35). */
export const DEMO_BLOCK: LessonView = {
  id: 'l-block-1tk1',
  pair: 1,
  startsAt: '08:30',
  endsAt: '13:10',
  discipline: DISCIPLINES.zu,
  teacher: T.yemelianenko,
  room: ROOMS.r204,
  group: { id: 'group-1tk1', code: '1ТК1', course: 1 },
  subgroup: 0,
  kind: 'BLOCK',
  online: false,
  original: null,
  reasonText: '1–4 пари',
  changeId: 'change-block',
};

/** Поля OptionDto, яких демо-варіанти не мають (core надсилає їх як null). */
const NO_EXTRAS = { room: null, swapLesson: null, date: null, pair: null, suggestedRoom: null } as const;

/** Варіанти в порядку сервера (рекомендатор v2): без балів, з чипами причин і застережень. */
export const DEMO_OPTIONS: OptionDto[] = [
  {
    kind: 'REPLACE_DISCIPLINE',
    score: 100,
    title: 'НДЕКСМ — Непийвода',
    reasons: ['НДЕКСМ відстає від плану', 'Закриває вікно в розкладі викладача', 'Сьогодні має 1, 3 пари'],
    teacher: T.nepyivoda,
    discipline: { id: 'd-ndeksm', shortName: 'НДЕКСМ', name: 'НДЕКСМ', color: null },
    ...NO_EXTRAS,
    suggestedRoom: ROOMS.r14,
    warnings: [],
    band: 'CLEAN',
    recommended: true,
  },
  {
    kind: 'REPLACE_DISCIPLINE',
    score: 90,
    title: 'Прогр. — Стець',
    reasons: ['Сьогодні має 2 пари'],
    teacher: T.stets,
    discipline: { id: 'd-prog', shortName: 'Прогр.', name: 'Програмування', color: null },
    ...NO_EXTRAS,
    suggestedRoom: ROOMS.r301,
    warnings: ['Вже має пару з цією групою (1 пара) — краще перестановка'],
    band: 'CAUTION',
    recommended: false,
  },
  {
    kind: 'SWAP',
    score: 80,
    title: 'Переставити 4 пару (Прогр.) на 2',
    reasons: ['Група піде раніше'],
    teacher: T.stets,
    discipline: null,
    ...NO_EXTRAS,
    warnings: [],
    band: 'CLEAN',
    recommended: false,
  },
  {
    kind: 'CANCEL',
    score: 70,
    title: 'Пари не буде',
    reasons: ['Перша пара: група прийде пізніше'],
    teacher: null,
    discipline: null,
    ...NO_EXTRAS,
    warnings: [],
    band: 'EASY_CANCEL',
    recommended: false,
  },
];
