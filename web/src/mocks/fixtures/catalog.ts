/**
 * Довідники мок-API: відділення, спеціальності, групи, викладачі, дисципліни, аудиторії.
 * Джерело — реальна таблиця seed/schedule-2025-26.xlsx (timetable.data.ts) і docs/DOMAIN.md.
 * Чого в документах немає (місткість, корпус, циклова комісія, розмір групи, ім'я викладача) — null,
 * невідомі назви дисциплін і спеціальностей лишаються абревіатурами з `needsReview` (DOMAIN §10: не вигадувати).
 */
import type {
  DepartmentDto,
  DisciplineDto,
  DisciplineRef,
  GroupDto,
  GroupRef,
  PublicGroupDto,
  PublicRoomDto,
  PublicTeacherDto,
  RoomDto,
  RoomRef,
  RoomType,
  SpecialtyDto,
  SubgroupDto,
  TeacherDto,
  TeacherRef,
  Uuid,
} from '@/api/types';
import { mockId } from '../ids';
import { RAW_GROUPS, RAW_LESSONS } from './timetable.data';

const UK = new Intl.Collator('uk');

// ───────────────────────── Відділення і спеціальності ─────────────────────────

/** Відділення та літери спеціальностей, що до них належать (DOMAIN §12). */
const DEPARTMENT_SOURCES: ReadonlyArray<{ name: string; letters: readonly string[] }> = [
  { name: 'Компʼютерне відділення', letters: ['КІ'] },
  { name: 'Фінансово-економічне відділення', letters: ['Ф', 'ОБ', 'М', 'ЕП', 'ОД'] },
  { name: 'Радіотехнічне відділення', letters: ['РТ', 'ТК'] },
  { name: 'Відділення електроенергетики та електромеханіки', letters: ['ЕА', 'ВЕ'] },
  { name: 'Відділення транспортних технологій', letters: ['ОП'] },
];

/** Спеціальності, назви яких підтверджені документами (DOMAIN §3, навчальний план КІ). */
const KNOWN_SPECIALTIES: Readonly<Record<string, { code: string; name: string }>> = {
  'КІ': { code: '123', name: 'Компʼютерна інженерія' },
};

export const DEPARTMENTS: readonly DepartmentDto[] = DEPARTMENT_SOURCES.map((source) => ({
  id: mockId('department', source.name),
  name: source.name,
  shortName: null,
}));

/** Спеціальність: код у базі може бути порожнім (дані імпорту його не містять). */
export type MockSpecialty = Omit<SpecialtyDto, 'code'> & { code: string | null };

export const SPECIALTIES: readonly MockSpecialty[] = DEPARTMENT_SOURCES.flatMap((source, index) =>
  source.letters.map((letters) => {
    const known = KNOWN_SPECIALTIES[letters];
    return {
      id: mockId('specialty', letters),
      code: known?.code ?? null,
      letters,
      // невідома повна назва — показуємо абревіатуру, як у розкладі
      name: known?.name ?? letters,
      departmentId: (DEPARTMENTS[index] as DepartmentDto).id,
    };
  }),
);

// ───────────────────────── Аудиторії ─────────────────────────

/** Спортзал приймає кілька груп одночасно (DOMAIN §11: «с.з.», max_parallel 3). */
const SPECIAL_ROOMS: Readonly<Record<string, { type: RoomType; maxParallel: number }>> = {
  'с.з.': { type: 'GYM', maxParallel: 3 },
  'с.п.': { type: 'SPORTS_GROUND', maxParallel: 1 },
};

function compareRoomNames(a: string, b: string): number {
  const na = Number(a);
  const nb = Number(b);
  const aNumeric = Number.isInteger(na) && a.trim() !== '';
  const bNumeric = Number.isInteger(nb) && b.trim() !== '';
  if (aNumeric && bNumeric) return na - nb;
  if (aNumeric !== bNumeric) return aNumeric ? -1 : 1;
  return UK.compare(a, b);
}

const roomNames = new Set<string>();
for (const [, , , , , , , room] of RAW_LESSONS) if (room) roomNames.add(room);
for (const [, , homeRoom] of RAW_GROUPS) if (homeRoom) roomNames.add(homeRoom);

export const ROOMS: readonly RoomDto[] = [...roomNames].sort(compareRoomNames).map((name) => ({
  id: mockId('room', name),
  building: null,
  name,
  type: SPECIAL_ROOMS[name]?.type ?? 'CLASSROOM',
  capacity: null,
  maxParallel: SPECIAL_ROOMS[name]?.maxParallel ?? 1,
  active: true,
  version: 0,
}));

// ───────────────────────── Викладачі ─────────────────────────

/** Викладачі, привʼязані до демо-облікових записів Keycloak (DOMAIN §12; користувачі krentsin, nepyivoda). */
export const LINKED_TEACHER_NAMES: readonly string[] = ['Кренцін', 'Непийвода'];

function surnameOf(shortName: string): string {
  return shortName.split(' ')[0] ?? shortName;
}

/** «Поліщук Л.І.» → «Поліщук Л.»: однофамільців розрізняємо першим ініціалом. */
function withFirstInitial(shortName: string): string {
  const [surname, initials = ''] = shortName.split(' ');
  const first = /^[^.]+\./.exec(initials)?.[0];
  return first ? `${surname} ${first}` : shortName;
}

const teacherNames = new Set<string>();
for (const [, , , , , , teacher] of RAW_LESSONS) if (teacher) teacherNames.add(teacher);
for (const [, , , curator] of RAW_GROUPS) if (curator) teacherNames.add(curator);

const surnameCount = new Map<string, number>();
for (const name of teacherNames) surnameCount.set(surnameOf(name), (surnameCount.get(surnameOf(name)) ?? 0) + 1);

export const TEACHERS: readonly TeacherDto[] = [...teacherNames].sort((a, b) => UK.compare(a, b)).map((shortName) => {
  const lastName = surnameOf(shortName);
  return {
    id: mockId('teacher', shortName),
    lastName,
    firstName: null,
    middleName: null,
    shortName,
    displayName: (surnameCount.get(lastName) ?? 0) > 1 ? withFirstInitial(shortName) : lastName,
    cycleCommission: null,
    employment: 'STAFF',
    email: null,
    active: true,
    needsReview: false,
    linked: LINKED_TEACHER_NAMES.includes(shortName),
    version: 0,
  };
});

// ───────────────────────── Дисципліни ─────────────────────────

/**
 * Повні назви лише там, де вони певні: DOMAIN §10 (відомі абревіатури), §4 (навчальний план КІ,
 * 7 семестр) і дисципліни 4КІ. Решта абревіатур лишаються назвами з `needsReview: true`.
 */
const KNOWN_DISCIPLINES: Readonly<Record<string, string>> = {
  'Матем.': 'Математика',
  'Укр.мова': 'Українська мова',
  'Укр.мов.': 'Українська мова',
  'Укр.літ.': 'Українська література',
  'Зар.літ.': 'Зарубіжна література',
  'Ін.мова': 'Іноземна мова',
  'Вс.іст.': 'Всесвітня історія',
  'Іст.Укр.': 'Історія України',
  'Геогр.': 'Географія',
  'БіЕ': 'Біологія і екологія',
  'ФіА': 'Фізика і астрономія',
  'Хімія': 'Хімія',
  'Інф.': 'Інформатика',
  'ФК': 'Фізична культура',
  'ФВ': 'Фізичне виховання',
  'ГО': 'Громадянська освіта',
  'Культ.': 'Культурологія',
  'ЗУ': 'Захист України',
  'БЖ': 'Безпека життєдіяльності',
  'Вст.спц.': 'Вступ до спеціальності',
  'СТП': 'Сучасні технології проєктування',
  'НДЕКСМ': 'Надійність, діагностика та експлуатація компʼютерних систем і мереж',
  'ОПЦЗ': 'Охорона праці та цивільний захист',
  'ТПМР': 'Технології проєктування мережевих ресурсів',
  'ТОЕ': 'Теоретичні основи електротехніки',
  'ПДР': 'Правила дорожнього руху',
  'ІКТ': 'Інформаційно-комунікаційні технології',
  'КМ': 'Компʼютерні мережі',
  'ЕУ': 'Економіка та управління ІТ-проєктами',
  'СП': 'Системне програмування',
  'КБ': 'Кібербезпека',
};

const disciplineNames = new Set<string>();
for (const [, , , , , discipline] of RAW_LESSONS) disciplineNames.add(discipline);

export const DISCIPLINES: readonly DisciplineDto[] = [...disciplineNames].sort((a, b) => UK.compare(a, b)).map((shortName) => ({
  id: mockId('discipline', shortName),
  name: KNOWN_DISCIPLINES[shortName] ?? shortName,
  shortName,
  // колір не задано — інтерфейс виводить його детерміновано з id (lib/discipline-color)
  color: null,
  active: true,
  needsReview: !(shortName in KNOWN_DISCIPLINES),
  version: 0,
}));

// ───────────────────────── Посилання (Ref) ─────────────────────────

export const groupRef = (group: GroupDto): GroupRef => ({ id: group.id, code: group.code, course: group.course });

export const teacherRef = (teacher: TeacherDto): TeacherRef => ({
  id: teacher.id,
  shortName: teacher.shortName,
  displayName: teacher.displayName,
});

export const disciplineRef = (discipline: DisciplineDto): DisciplineRef => ({
  id: discipline.id,
  shortName: discipline.shortName,
  name: discipline.name,
  color: discipline.color,
});

export const roomRef = (room: RoomDto): RoomRef => ({ id: room.id, name: room.name, type: room.type });

// ───────────────────────── Пошук за ключами ─────────────────────────

function indexBy<T>(items: readonly T[], key: (item: T) => string): ReadonlyMap<string, T> {
  return new Map(items.map((item) => [key(item), item]));
}

const teacherById = indexBy(TEACHERS, (t) => t.id);
const teacherByName = indexBy(TEACHERS, (t) => t.shortName);
const disciplineById = indexBy(DISCIPLINES, (d) => d.id);
const disciplineByName = indexBy(DISCIPLINES, (d) => d.shortName);
const roomById = indexBy(ROOMS, (r) => r.id);
const roomByName = indexBy(ROOMS, (r) => r.name);

export const findTeacher = (id: Uuid): TeacherDto | undefined => teacherById.get(id);
export const findDiscipline = (id: Uuid): DisciplineDto | undefined => disciplineById.get(id);
export const findRoom = (id: Uuid): RoomDto | undefined => roomById.get(id);
export const findRoomByName = (name: string): RoomDto | undefined => roomByName.get(name);

/** Для вбудованих даних: відсутність запису — помилка в даних, а не «немає результату». */
function required<T>(value: T | undefined, what: string): T {
  if (value === undefined) throw new Error(`Мок-дані неузгоджені: не знайдено ${what}`);
  return value;
}

export const teacherNamed = (shortName: string): TeacherDto => required(teacherByName.get(shortName), `викладача «${shortName}»`);
export const disciplineNamed = (shortName: string): DisciplineDto =>
  required(disciplineByName.get(shortName), `дисципліну «${shortName}»`);
export const roomNamed = (name: string): RoomDto => required(roomByName.get(name), `аудиторію «${name}»`);

// ───────────────────────── Групи ─────────────────────────

const GROUP_CODE_RE = /^(\d)([А-ЯІЇЄҐ]{1,3})(\d?)$/u;

/** Групи з підгрупами в розкладі: для них створюємо підгрупи 1 і 2. */
const subgroupCounts = new Map<string, number>();
for (const [code, , , , subgroup] of RAW_LESSONS) {
  subgroupCounts.set(code, Math.max(subgroupCounts.get(code) ?? 0, subgroup));
}

function subgroupsOf(code: string): SubgroupDto[] {
  const count = subgroupCounts.get(code) ?? 0;
  return Array.from({ length: count }, (_, index) => ({
    id: mockId('subgroup', `${code}/${index + 1}`),
    number: index + 1,
    name: `${index + 1} підгрупа`,
    size: null,
  }));
}

export const GROUPS: readonly GroupDto[] = RAW_GROUPS.map(([code, course, homeRoom, curator]) => {
  const match = GROUP_CODE_RE.exec(code);
  if (!match) throw new Error(`Мок-дані неузгоджені: код групи «${code}» не відповідає формату`);
  const letters = match[2] ?? '';
  const specialty = required(
    SPECIALTIES.find((s) => s.letters === letters),
    `спеціальність «${letters}» групи ${code}`,
  );
  return {
    id: mockId('group', code),
    code,
    course,
    specialty: { id: specialty.id, label: specialty.name },
    number: match[3] ? Number(match[3]) : null,
    size: null,
    // DOMAIN §2: у реальному розкладі 2 курс навчається в другу зміну
    shift: course === 2 ? 2 : 1,
    curator: curator ? teacherRef(teacherNamed(curator)) : null,
    homeRoom: homeRoom ? roomRef(roomNamed(homeRoom)) : null,
    active: true,
    subgroups: subgroupsOf(code),
    version: 0,
  };
});

const groupById = indexBy(GROUPS, (g) => g.id);
const groupByCode = indexBy(GROUPS, (g) => g.code);

export const findGroup = (id: Uuid): GroupDto | undefined => groupById.get(id);
export const findGroupByCode = (code: string): GroupDto | undefined => groupByCode.get(code);
export const groupNamed = (code: string): GroupDto => required(groupByCode.get(code), `групу «${code}»`);

export function specialtyOf(group: GroupDto): MockSpecialty {
  return required(
    SPECIALTIES.find((s) => s.id === group.specialty?.id),
    `спеціальність групи ${group.code}`,
  );
}

export function departmentOf(group: GroupDto): DepartmentDto {
  const specialty = specialtyOf(group);
  return required(
    DEPARTMENTS.find((d) => d.id === specialty.departmentId),
    `відділення групи ${group.code}`,
  );
}

// ───────────────────────── Публічні DTO ─────────────────────────

export function toPublicGroup(group: GroupDto): PublicGroupDto {
  const specialty = specialtyOf(group);
  return {
    id: group.id,
    code: group.code,
    course: group.course,
    specialty: { letters: specialty.letters, name: specialty.name },
    department: departmentOf(group).name,
    shift: group.shift,
  };
}

/**
 * Як у core: `fullName` лише тоді, коли налаштування showTeacherFullNames це дозволяє й імʼя відоме.
 * Мок не читає налаштувань (типово false), тож повного імені не дає — як core з типовими налаштуваннями.
 */
export const toPublicTeacher = (teacher: TeacherDto): PublicTeacherDto => ({
  id: teacher.id,
  shortName: teacher.shortName,
  displayName: teacher.displayName,
  cycleCommission: teacher.cycleCommission?.label ?? null,
  fullName: null,
});

export const toPublicRoom = (room: RoomDto): PublicRoomDto => ({
  id: room.id,
  name: room.name,
  type: room.type,
  capacity: room.capacity,
  building: room.building?.label ?? null,
  maxParallel: room.maxParallel,
});
