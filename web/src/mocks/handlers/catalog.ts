/**
 * Каталог диспетчера (CONTRACTS §2 «Catalog»): CRUD довідників зі сторінками, пошуком і сортуванням,
 * псевдоніми, компетенції викладачів, підгрупи. Правила — як у core (catalog/internal/*Service):
 * 400 з `errors[]` на погані поля, 409 на дублікати й на видалення використаного запису, 409 «Дані змінилися»
 * на застарілу `version`.
 *
 * Початкові дані — з fixtures (реальна таблиця розкладу). Циклових комісій і корпусів у документах немає,
 * тому їх довідники спершу порожні (DOMAIN: не вигадувати).
 */
import { http, HttpResponse } from 'msw';
import type {
  CatalogBuilding,
  CatalogCycleCommission,
  CatalogDepartment,
  CatalogDiscipline,
  CatalogEntity,
  CatalogRow,
  CatalogGroup,
  CatalogRoom,
  CatalogSpecialty,
  CatalogSubgroup,
  CatalogTeacher,
  DepartmentRequest,
  DisciplineRequest,
  GroupRequest,
  NameRequest,
  RoomRequest,
  SpecialtyRequest,
  SubgroupRequest,
  TeacherRequest,
} from '@/api/catalog';
import type {
  AliasDto,
  DisciplineRef,
  Employment,
  FieldErrorDto,
  GroupRef,
  Page,
  Ref,
  RoomRef,
  RoomType,
  TeacherRef,
  Uuid,
} from '@/api/types';
import { shortNameOf } from '@/components/catalog/names';
import { countLabel } from '@/lib/labels';
import { canonicalGroupCode, normalizeText } from '@/lib/search';
import { ukCollator } from '@/lib/sort';
import {
  DEPARTMENTS,
  DISCIPLINES,
  GROUPS,
  ROOMS,
  SPECIALTIES,
  TEACHERS,
  TEMPLATE_LESSONS,
} from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, conflict, problem, readJson } from '../kit';
import { createMockCollection, type MockCollection } from '../store';

// ───────────────────────── Стан ─────────────────────────

type AnyCatalogRow = CatalogRow<CatalogEntity>;

const ROOM_TYPES: readonly RoomType[] = ['CLASSROOM', 'COMPUTER_LAB', 'LAB', 'GYM', 'SPORTS_GROUND', 'OTHER'];
const EMPLOYMENTS: readonly Employment[] = ['STAFF', 'PART_TIME'];

export const departments = createMockCollection<CatalogDepartment>('Відділення', () =>
  DEPARTMENTS.map((d) => ({ ...d, version: 0 })),
);

export const specialties = createMockCollection<CatalogSpecialty>('Спеціальність', () =>
  SPECIALTIES.map((s) => {
    const department = DEPARTMENTS.find((d) => d.id === s.departmentId);
    return { ...s, department: department ? { id: department.id, label: department.name } : null, version: 0 };
  }),
);

export const cycleCommissions = createMockCollection<CatalogCycleCommission>('Циклову комісію', () => []);

export const buildings = createMockCollection<CatalogBuilding>('Корпус', () => []);

export const rooms = createMockCollection<CatalogRoom>('Аудиторію', () => ROOMS.map((r) => ({ ...r, version: 0 })));

export const disciplines = createMockCollection<CatalogDiscipline>('Дисципліну', () =>
  DISCIPLINES.map((d) => ({ ...d, version: 0 })),
);

export const teachers = createMockCollection<CatalogTeacher>('Викладача', () =>
  TEACHERS.map((t) => ({ ...t, linked: t.linked ?? false, version: 0 })),
);

export const groups = createMockCollection<CatalogGroup>('Групу', () =>
  GROUPS.map((g) => {
    const specialty = SPECIALTIES.find((s) => s.id === g.specialty?.id);
    return {
      ...g,
      // як у core: підпис спеціальності в групі — її літери
      specialty: specialty ? { id: specialty.id, label: specialty.letters } : null,
      specialtyId: specialty?.id ?? null,
      subgroups: (g.subgroups ?? []).map((s) => ({ ...s, version: 0 })),
      version: 0,
    };
  }),
);

interface StoredAlias extends AliasDto {
  ownerId: Uuid;
}

/**
 * Псевдоніми з DOMAIN §10: написання, які трапляються в таблиці й розпорядженнях. «Граболінська» і
 * «Герасменко» навмисно не додано: їх «знаходить» демо-імпорт таблиці (handlers/import.ts).
 */
const TEACHER_ALIAS_SEED: ReadonlyArray<readonly [teacher: string, alias: string]> = [
  ['Харченко О.А.', 'Хорченко О.'],
  ['Харченко Г.Ю.', 'ХарченкоГ.'],
  ['Вернигора', 'Вернигорв'],
];
const DISCIPLINE_ALIAS_SEED: ReadonlyArray<readonly [discipline: string, alias: string]> = [
  ['Укр.мова', 'Укр.мова.'],
  ['ФК', 'Фк'],
  ['Вс.іст.', 'Вс.Іст.'],
  ['Вст.спц.', 'Вст.спц'],
  ['НДЕКСМ', 'НДКС'],
];

function seedAliases(kind: 'teacher' | 'discipline', pairs: ReadonlyArray<readonly [string, string]>): StoredAlias[] {
  return pairs.map(([owner, alias]) => {
    const ownerId =
      kind === 'teacher'
        ? TEACHERS.find((t) => t.shortName === owner)?.id
        : DISCIPLINES.find((d) => d.shortName === owner)?.id;
    if (!ownerId) throw new Error(`Мок-дані неузгоджені: немає «${owner}» для псевдоніма «${alias}»`);
    return { id: mockId(`${kind}-alias`, alias), alias, ownerId };
  });
}

export const teacherAliases = createMockCollection<StoredAlias>('Псевдонім', () => seedAliases('teacher', TEACHER_ALIAS_SEED));
export const disciplineAliases = createMockCollection<StoredAlias>('Псевдонім', () =>
  seedAliases('discipline', DISCIPLINE_ALIAS_SEED),
);

interface Competence {
  id: Uuid;
  teacherId: Uuid;
  disciplineId: Uuid;
}

/** Компетенції: дисципліни, які викладач веде за опублікованим розкладом (як `disciplinesOfTeacher`). */
export const competences = createMockCollection<Competence>('Компетенцію', () => {
  const seen = new Set<string>();
  const out: Competence[] = [];
  for (const lesson of TEMPLATE_LESSONS) {
    if (!lesson.teacher) continue;
    const key = `${lesson.teacher.id}|${lesson.discipline.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ id: mockId('competence', key), teacherId: lesson.teacher.id, disciplineId: lesson.discipline.id });
  }
  return out;
});

// ───────────────────────── Посилання для інших областей мок-API ─────────────────────────

export const groupRefOf = (group: CatalogGroup): GroupRef => ({ id: group.id, code: group.code, course: group.course });
export const teacherRefOf = (teacher: CatalogTeacher): TeacherRef => ({
  id: teacher.id,
  shortName: teacher.shortName,
  displayName: teacher.displayName,
});
export const disciplineRefOf = (discipline: CatalogDiscipline): DisciplineRef => ({
  id: discipline.id,
  shortName: discipline.shortName,
  name: discipline.name,
  color: discipline.color,
});
export const roomRefOf = (room: CatalogRoom): RoomRef => ({ id: room.id, name: room.name, type: room.type });

/**
 * Хто ще посилається на записи каталогу (навантаження, імпорт…): інші області реєструють перевірку,
 * і видалення використаного запису дає 409, як FK у справжній базі.
 */
type UsageCheck = (entity: CatalogEntity | 'subgroup', id: Uuid, extra?: { subgroup?: number }) => string | null;
const usageChecks: UsageCheck[] = [];

export function registerCatalogUsage(check: UsageCheck): void {
  usageChecks.push(check);
}

function externalUsage(entity: CatalogEntity | 'subgroup', id: Uuid, extra?: { subgroup?: number }): string | null {
  for (const check of usageChecks) {
    const usage = check(entity, id, extra);
    if (usage) return usage;
  }
  return null;
}

// ───────────────────────── Сторінки, пошук, сортування ─────────────────────────

const MAX_PAGE_SIZE = 200;

/** Page<T> за правилами PageQuery core: page ≥ 0, size 1..200 (типово 20). */
function pageOf<T>(items: readonly T[], url: URL): Page<T> {
  const errors: FieldErrorDto[] = [];
  const page = Number(url.searchParams.get('page') ?? 0);
  const size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isInteger(page) || page < 0) errors.push({ field: 'page', message: 'Номер сторінки має бути цілим числом, не меншим за 0.' });
  if (!Number.isInteger(size) || size < 1 || size > MAX_PAGE_SIZE) {
    errors.push({ field: 'size', message: `Розмір сторінки має бути цілим числом від 1 до ${MAX_PAGE_SIZE}.` });
  }
  if (errors.length > 0) throw badRequest('Некоректні параметри сторінки.', errors);
  return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length };
}

type SortValue = string | number | boolean | null | undefined;

/** `sort=поле,asc|desc`; невідоме поле — 400, як у core. Порожні значення — у кінці. */
function sortBy<T>(items: T[], url: URL, fields: Readonly<Record<string, (item: T) => SortValue>>, fallback: string): T[] {
  const raw = url.searchParams.get('sort') ?? fallback;
  const [field = '', direction = 'asc'] = raw.split(',');
  const getter = fields[field];
  if (!getter || (direction !== 'asc' && direction !== 'desc')) {
    throw badRequest('Некоректне сортування.', [
      { field: 'sort', message: `Сортувати можна за полями: ${Object.keys(fields).join(', ')} (напрям asc або desc).` },
    ]);
  }
  const factor = direction === 'desc' ? -1 : 1;
  return [...items].sort((a, b) => {
    const va = getter(a);
    const vb = getter(b);
    const emptyA = va === null || va === undefined || va === '';
    const emptyB = vb === null || vb === undefined || vb === '';
    if (emptyA || emptyB) return emptyA === emptyB ? 0 : emptyA ? 1 : -1;
    if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * factor;
    if (typeof va === 'boolean' && typeof vb === 'boolean') return (Number(va) - Number(vb)) * factor;
    return ukCollator.compare(String(va), String(vb)) * factor;
  });
}

function matchesQuery(q: string | null, ...values: Array<string | null | undefined>): boolean {
  if (!q || !q.trim()) return true;
  const needle = normalizeText(q);
  return values.some((value) => value != null && normalizeText(value).includes(needle));
}

function boolParam(url: URL, name: string): boolean | undefined {
  const raw = url.searchParams.get(name);
  if (raw === null || raw === '') return undefined;
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  throw badRequest(`Параметр ${name} має бути true або false.`, [{ field: name, message: 'Очікується true або false.' }]);
}

// ───────────────────────── Перевірки ─────────────────────────

class Errors {
  readonly list: FieldErrorDto[] = [];
  add(field: string, message: string): void {
    this.list.push({ field, message });
  }
  throwIfAny(detail = 'Перевірте виділені поля.'): void {
    if (this.list.length > 0) throw badRequest(detail, this.list);
  }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function optionalText(value: unknown): string | null {
  const trimmed = text(value);
  return trimmed === '' ? null : trimmed;
}

/** Обовʼязковий текст: `empty` — повідомлення, коли поле порожнє («Вкажіть назву аудиторії.»). */
function requiredText(errors: Errors, field: string, value: unknown, max: number, empty: string): string {
  const trimmed = text(value);
  if (!trimmed) errors.add(field, empty);
  else if (trimmed.length > max) errors.add(field, `Не довше за ${max} символів.`);
  return trimmed;
}

function intInRange(errors: Errors, field: string, value: unknown, min: number, max: number): number | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    errors.add(field, `Ціле число від ${min} до ${max}.`);
    return null;
  }
  return value;
}

/** Ключ без регістру й розділювачів: «С.З.» = «с.з.», як у core. */
const looseKey = (value: string) => normalizeText(value).replace(/[\s.\-_]+/g, '');

function staleData(): Response {
  return problem({
    status: 409,
    slug: 'stale-data',
    title: 'Дані змінилися',
    detail: 'Цей запис уже змінив хтось інший. Закрийте форму, оновіть список і внесіть зміни ще раз.',
  });
}

function checkVersion(current: { version: number }, expected: number | undefined | null): void {
  if (expected !== undefined && expected !== null && expected !== current.version) throw staleData();
}

function usedBy(lessonCount: number, extra: string | null): boolean {
  return lessonCount > 0 || extra !== null;
}

// ───────────────────────── Відділення, спеціальності, комісії, корпуси ─────────────────────────

function saveDepartment(body: DepartmentRequest, id?: Uuid): CatalogDepartment {
  const errors = new Errors();
  const name = requiredText(errors, 'name', body.name, 200, 'Вкажіть назву відділення.');
  const shortName = optionalText(body.shortName);
  if (shortName && shortName.length > 50) errors.add('shortName', 'Не довше за 50 символів.');
  errors.throwIfAny();
  const twin = departments.all().find((d) => d.id !== id && looseKey(d.name) === looseKey(name));
  if (twin) throw conflict(`Відділення «${twin.name}» уже існує.`);
  if (id) {
    return departments.update(id, (current) => {
      checkVersion(current, body.version);
      return { ...current, name, shortName, version: current.version + 1 };
    });
  }
  return departments.insert({ id: mockId('department', crypto.randomUUID()), name, shortName, version: 0 });
}

const LETTERS_RE = /^[А-ЯІЇЄҐ]{1,3}$/u;

function saveSpecialty(body: SpecialtyRequest, id?: Uuid): CatalogSpecialty {
  const errors = new Errors();
  // латинські двійники → кирилиця, як у core («KI» → «КІ»)
  const letters = (canonicalGroupCode(`1${text(body.letters)}`)?.slice(1) ?? text(body.letters).toLocaleUpperCase('uk'));
  if (!LETTERS_RE.test(letters)) errors.add('letters', 'Літери спеціальності — 1–3 українські літери, як у коді групи (наприклад, КІ).');
  const name = requiredText(errors, 'name', body.name, 200, 'Вкажіть назву спеціальності.');
  const code = optionalText(body.code);
  if (code && !/^\d{3}$/.test(code)) errors.add('code', 'Код спеціальності — три цифри, наприклад 123.');
  const departmentId = body.departmentId ?? null;
  const department = departmentId ? departments.find(departmentId) : null;
  if (departmentId && !department) errors.add('departmentId', 'Відділення не знайдено.');
  errors.throwIfAny();
  const twin = specialties.all().find((s) => s.id !== id && s.letters === letters);
  if (twin) throw conflict(`Спеціальність з літерами «${letters}» уже існує.`);
  const departmentRef = department ? { id: department.id, label: department.name } : null;
  if (id) {
    return specialties.update(id, (current) => {
      checkVersion(current, body.version);
      if (current.letters !== letters && groups.all().some((g) => g.specialtyId === id)) {
        throw conflict(`Літери «${current.letters}» не можна змінити: вони входять у коди груп цієї спеціальності.`);
      }
      return { ...current, code, letters, name, departmentId, department: departmentRef, version: current.version + 1 };
    });
  }
  return specialties.insert({
    id: mockId('specialty', crypto.randomUUID()),
    code,
    letters,
    name,
    departmentId,
    department: departmentRef,
    version: 0,
  });
}

function saveNamed<T extends { id: Uuid; name: string; version: number }>(
  collection: MockCollection<T>,
  kind: string,
  duplicate: (name: string) => string,
  body: NameRequest,
  id?: Uuid,
): T {
  const errors = new Errors();
  const name = requiredText(errors, 'name', body.name, 200, 'Вкажіть назву.');
  errors.throwIfAny();
  const twin = collection.all().find((item) => item.id !== id && looseKey(item.name) === looseKey(name));
  if (twin) throw conflict(duplicate(twin.name));
  if (id) {
    return collection.update(id, (current) => {
      checkVersion(current, body.version);
      return { ...current, name, version: current.version + 1 };
    });
  }
  return collection.insert({ id: mockId(kind, crypto.randomUUID()), name, version: 0 } as T);
}

// ───────────────────────── Аудиторії ─────────────────────────

function saveRoom(body: RoomRequest, id?: Uuid): CatalogRoom {
  const errors = new Errors();
  const name = requiredText(errors, 'name', body.name, 50, 'Вкажіть назву або номер аудиторії.');
  if (name && !/[\p{L}\p{N}]/u.test(name)) errors.add('name', 'Назва аудиторії має містити літери або цифри.');
  if (!ROOM_TYPES.includes(body.type)) errors.add('type', 'Оберіть тип приміщення.');
  const capacity = intInRange(errors, 'capacity', body.capacity, 0, 1000);
  const maxParallel = intInRange(errors, 'maxParallel', body.maxParallel, 1, 20) ?? 1;
  const building = body.buildingId ? buildings.find(body.buildingId) : null;
  if (body.buildingId && !building) errors.add('buildingId', 'Корпус не знайдено.');
  errors.throwIfAny();
  const twin = rooms.all().find((r) => r.id !== id && looseKey(r.name) === looseKey(name));
  if (twin) throw conflict(`Аудиторія «${twin.name}» уже існує.`);
  const fields = {
    name,
    type: body.type,
    capacity,
    maxParallel,
    building: building ? { id: building.id, label: building.name } : null,
    active: body.active ?? true,
  };
  if (id) {
    return rooms.update(id, (current) => {
      checkVersion(current, body.version);
      return { ...current, ...fields, version: current.version + 1 };
    });
  }
  return rooms.insert({ id: mockId('room', crypto.randomUUID()), ...fields, version: 0 });
}

// ───────────────────────── Дисципліни ─────────────────────────

function saveDiscipline(body: DisciplineRequest, id?: Uuid): CatalogDiscipline {
  const errors = new Errors();
  const name = requiredText(errors, 'name', body.name, 300, 'Вкажіть повну назву дисципліни.');
  const shortName = requiredText(errors, 'shortName', body.shortName, 64, 'Вкажіть скорочену назву, як у розкладі.');
  if (shortName && !/[\p{L}\p{N}]/u.test(shortName)) errors.add('shortName', 'Скорочена назва має містити літери або цифри.');
  const color = optionalText(body.color);
  if (color && !/^#[0-9A-Fa-f]{6}$/.test(color)) errors.add('color', 'Колір має вигляд #RRGGBB, наприклад #1F6FEB.');
  errors.throwIfAny();
  const twin = disciplines.all().find((d) => d.id !== id && looseKey(d.shortName) === looseKey(shortName));
  if (twin) throw conflict(`Дисципліна «${twin.shortName}» уже існує.`);
  if (disciplineAliases.all().some((a) => a.ownerId !== id && looseKey(a.alias) === looseKey(shortName))) {
    throw conflict(`Назва «${shortName}» уже записана як псевдонім іншої дисципліни.`);
  }
  const fields = {
    name,
    shortName,
    color: color ? color.toLowerCase() : null,
    active: body.active ?? true,
    needsReview: body.needsReview ?? false,
  };
  if (id) {
    return disciplines.update(id, (current) => {
      checkVersion(current, body.version);
      return { ...current, ...fields, version: current.version + 1 };
    });
  }
  return disciplines.insert({ id: mockId('discipline', crypto.randomUUID()), ...fields, version: 0 });
}

// ───────────────────────── Викладачі ─────────────────────────

function saveTeacher(body: TeacherRequest, id?: Uuid): CatalogTeacher {
  const errors = new Errors();
  const lastName = requiredText(errors, 'lastName', body.lastName, 100, 'Вкажіть прізвище.');
  const firstName = optionalText(body.firstName);
  const middleName = optionalText(body.middleName);
  const shortName = optionalText(body.shortName) ?? shortNameOf(lastName, firstName, middleName);
  if (shortName && !/\p{L}/u.test(shortName)) errors.add('shortName', "Коротке ім'я має містити літери.");
  const displayName = optionalText(body.displayName) ?? lastName;
  const email = optionalText(body.email);
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.add('email', 'Некоректна адреса електронної пошти');
  const employment = body.employment ?? 'STAFF';
  if (!EMPLOYMENTS.includes(employment)) errors.add('employment', 'Оберіть тип зайнятості.');
  const commission = body.cycleCommissionId ? cycleCommissions.find(body.cycleCommissionId) : null;
  if (body.cycleCommissionId && !commission) errors.add('cycleCommissionId', 'Циклову комісію не знайдено.');
  errors.throwIfAny();
  const twin = teachers.all().find((t) => t.id !== id && looseKey(t.shortName) === looseKey(shortName));
  if (twin) throw conflict(`Викладач «${twin.shortName}» уже існує.`);
  if (teacherAliases.all().some((a) => a.ownerId !== id && looseKey(a.alias) === looseKey(shortName))) {
    throw conflict(`Ім'я «${shortName}» уже записане як псевдонім іншого викладача.`);
  }
  const fields = {
    lastName,
    firstName,
    middleName,
    shortName,
    displayName,
    email,
    employment,
    cycleCommission: commission ? { id: commission.id, label: commission.name } : null,
    active: body.active ?? true,
    needsReview: body.needsReview ?? false,
  };
  if (id) {
    return teachers.update(id, (current) => {
      checkVersion(current, body.version);
      return { ...current, ...fields, version: current.version + 1 };
    });
  }
  return teachers.insert({ id: mockId('teacher', crypto.randomUUID()), ...fields, linked: false, version: 0 });
}

// ───────────────────────── Групи й підгрупи ─────────────────────────

const GROUP_CODE_FORMAT = 'Код групи має вигляд «4КІ1»: курс, літери спеціальності, номер.';

function saveGroup(body: GroupRequest, id?: Uuid): CatalogGroup {
  const errors = new Errors();
  const raw = text(body.code);
  const code = raw ? canonicalGroupCode(raw) : null;
  let course = 0;
  let letters = '';
  let number: number | null = null;
  if (!raw) errors.add('code', 'Код групи не може бути порожнім.');
  else if (!code) errors.add('code', GROUP_CODE_FORMAT);
  else {
    course = Number(code.charAt(0));
    const match = /^\d([А-ЯІЇЄҐ]{1,3})(\d?)$/u.exec(code);
    letters = match?.[1] ?? '';
    number = match?.[2] ? Number(match[2]) : null;
    if (course < 1 || course > 6) errors.add('code', 'Курс у коді групи має бути від 1 до 6.');
    if (body.course != null && body.course !== course) errors.add('course', `Курс не збігається з кодом групи (${course}).`);
    if (body.number != null && body.number !== number) errors.add('number', 'Номер не збігається з кодом групи.');
  }
  const size = intInRange(errors, 'size', body.size, 0, 200);
  const shift = body.shift ?? (course === 2 ? 2 : 1);
  if (shift !== 1 && shift !== 2) errors.add('shift', 'Зміна — 1 або 2.');
  const curator = body.curatorTeacherId ? teachers.find(body.curatorTeacherId) : null;
  if (body.curatorTeacherId && !curator) errors.add('curatorTeacherId', 'Викладача не знайдено.');
  const homeRoom = body.homeRoomId ? rooms.find(body.homeRoomId) : null;
  if (body.homeRoomId && !homeRoom) errors.add('homeRoomId', 'Аудиторію не знайдено.');
  let specialty = letters ? specialties.all().find((s) => s.letters === letters) : undefined;
  if (body.specialtyId) {
    const chosen = specialties.find(body.specialtyId);
    if (!chosen) errors.add('specialtyId', 'Спеціальність не знайдено.');
    else if (letters && chosen.letters !== letters) {
      errors.add('specialtyId', `Літери спеціальності (${chosen.letters}) не збігаються з кодом групи (${letters}).`);
    } else specialty = chosen;
  } else if (code && letters && !specialty) {
    errors.add('code', `Спеціальність «${letters}» не знайдено: спершу додайте її до довідника.`);
  }
  errors.throwIfAny();
  const finalCode = code as string;
  const twin = groups.all().find((g) => g.id !== id && g.code === finalCode);
  if (twin) throw conflict(`Група ${twin.code} уже існує.`);
  const fields = {
    code: finalCode,
    course,
    number,
    size,
    shift,
    specialtyId: specialty?.id ?? null,
    specialty: specialty ? { id: specialty.id, label: specialty.letters } : null,
    curator: curator ? teacherRefOf(curator) : null,
    homeRoom: homeRoom ? roomRefOf(homeRoom) : null,
    active: body.active ?? true,
  };
  if (id) {
    return groups.update(id, (current) => {
      checkVersion(current, body.version);
      return { ...current, ...fields, version: current.version + 1 };
    });
  }
  return groups.insert({ id: mockId('group', crypto.randomUUID()), ...fields, subgroups: [], version: 0 });
}

function subgroupUsage(group: CatalogGroup, number: number): string | null {
  const lessons = TEMPLATE_LESSONS.filter((l) => l.group.id === group.id && l.subgroup === number).length;
  const parts: string[] = [];
  if (lessons > 0) parts.push(`${countLabel(lessons, ['заняття', 'заняття', 'занять'])} у розкладі`);
  const extra = externalUsage('subgroup', group.id, { subgroup: number });
  if (extra) parts.push(extra);
  return parts.length > 0 ? parts.join('; ') : null;
}

function saveSubgroup(groupId: Uuid, body: SubgroupRequest, subgroupId?: Uuid): CatalogSubgroup {
  const group = groups.get(groupId);
  const errors = new Errors();
  const number = intInRange(errors, 'number', body.number, 1, 9);
  if (body.number === null || body.number === undefined) errors.add('number', 'Вкажіть номер підгрупи.');
  const name = optionalText(body.name);
  if (name && name.length > 100) errors.add('name', 'Не довше за 100 символів.');
  const size = intInRange(errors, 'size', body.size, 0, 200);
  errors.throwIfAny();
  const twin = group.subgroups.find((s) => s.id !== subgroupId && s.number === number);
  if (twin) throw conflict(`У групі ${group.code} уже є підгрупа ${number}.`);
  let saved: CatalogSubgroup | undefined;
  groups.update(groupId, (current) => {
    let subgroups: CatalogSubgroup[];
    if (subgroupId) {
      const existing = current.subgroups.find((s) => s.id === subgroupId);
      if (!existing) throw problem({ status: 404, detail: `Підгрупу з id ${subgroupId} не знайдено.` });
      checkVersion(existing, body.version);
      if (existing.number !== number) {
        const usage = subgroupUsage(current, existing.number);
        if (usage) throw conflict(`Номер підгрупи ${existing.number} групи ${current.code} не можна змінити: ${usage}.`);
      }
      saved = { ...existing, number: number as number, name, size, version: existing.version + 1 };
      subgroups = current.subgroups.map((s) => (s.id === subgroupId ? (saved as CatalogSubgroup) : s));
    } else {
      saved = { id: mockId('subgroup', crypto.randomUUID()), number: number as number, name, size, version: 0 };
      subgroups = [...current.subgroups, saved];
    }
    return { ...current, subgroups: subgroups.sort((a, b) => a.number - b.number) };
  });
  return saved as CatalogSubgroup;
}

// ───────────────────────── Видалення з перевіркою посилань ─────────────────────────

function lessonsWith(predicate: (lesson: (typeof TEMPLATE_LESSONS)[number]) => boolean): number {
  return TEMPLATE_LESSONS.filter(predicate).length;
}

function deleteOrConflict(entity: CatalogEntity, id: Uuid): void {
  switch (entity) {
    case 'rooms': {
      const room = rooms.get(id);
      const lessons = lessonsWith((l) => l.room?.id === id);
      const homeOf = groups.all().some((g) => g.homeRoom?.id === id);
      if (usedBy(lessons, externalUsage(entity, id)) || homeOf) {
        throw conflict(
          `Аудиторію «${room.name}» не можна видалити: вона є в розкладі, навантаженні або закріплена за групою. Позначте її неактивною.`,
        );
      }
      rooms.remove(id);
      return;
    }
    case 'groups': {
      const group = groups.get(id);
      if (usedBy(lessonsWith((l) => l.group.id === id), externalUsage(entity, id))) {
        throw conflict(`Групу «${group.code}» не можна видалити: вона є в розкладі, навантаженні або замінах. Позначте її неактивною.`);
      }
      groups.remove(id);
      return;
    }
    case 'teachers': {
      const teacher = teachers.get(id);
      const curates = groups.all().some((g) => g.curator?.id === id);
      if (usedBy(lessonsWith((l) => l.teacher?.id === id), externalUsage(entity, id)) || curates) {
        throw conflict(
          `Викладача «${teacher.shortName}» не можна видалити: у нього є заняття, навантаження, кураторство або заміни. Позначте його неактивним.`,
        );
      }
      teachers.remove(id);
      for (const alias of teacherAliases.all().filter((a) => a.ownerId === id)) teacherAliases.remove(alias.id);
      for (const item of competences.all().filter((c) => c.teacherId === id)) competences.remove(item.id);
      return;
    }
    case 'disciplines': {
      const discipline = disciplines.get(id);
      if (usedBy(lessonsWith((l) => l.discipline.id === id), externalUsage(entity, id))) {
        throw conflict(
          `Дисципліну «${discipline.shortName}» не можна видалити: вона є в розкладі, навантаженні або замінах. Позначте її неактивною.`,
        );
      }
      disciplines.remove(id);
      for (const alias of disciplineAliases.all().filter((a) => a.ownerId === id)) disciplineAliases.remove(alias.id);
      for (const item of competences.all().filter((c) => c.disciplineId === id)) competences.remove(item.id);
      return;
    }
    case 'buildings': {
      const building = buildings.get(id);
      if (rooms.all().some((r) => r.building?.id === id)) {
        throw conflict(`Корпус «${building.name}» не можна видалити: у ньому є аудиторії.`);
      }
      buildings.remove(id);
      return;
    }
    case 'departments': {
      const department = departments.get(id);
      if (specialties.all().some((s) => s.departmentId === id)) {
        throw conflict(`Відділення «${department.name}» не можна видалити: до нього належать спеціальності.`);
      }
      departments.remove(id);
      return;
    }
    case 'specialties': {
      const specialty = specialties.get(id);
      if (groups.all().some((g) => g.specialtyId === id)) {
        throw conflict(`Спеціальність «${specialty.letters}» не можна видалити: до неї належать групи.`);
      }
      specialties.remove(id);
      return;
    }
    case 'cycle-commissions': {
      const commission = cycleCommissions.get(id);
      if (teachers.all().some((t) => t.cycleCommission?.id === id)) {
        throw conflict(`Циклову комісію «${commission.name}» не можна видалити: до неї належать викладачі.`);
      }
      cycleCommissions.remove(id);
      return;
    }
  }
}

// ───────────────────────── Псевдоніми ─────────────────────────

function addTeacherAlias(teacherId: Uuid, rawAlias: unknown): AliasDto {
  const teacher = teachers.get(teacherId);
  const errors = new Errors();
  const alias = requiredText(errors, 'alias', rawAlias, 100, 'Вкажіть написання псевдоніма.');
  errors.throwIfAny();
  const owner = teachers.all().find((t) => looseKey(t.shortName) === looseKey(alias));
  if (owner) {
    throw conflict(
      owner.id === teacherId ? `«${alias}» — це вже основне ім'я цього викладача.` : `«${alias}» — основне ім'я іншого викладача.`,
    );
  }
  const existing = teacherAliases.all().find((a) => looseKey(a.alias) === looseKey(alias));
  if (existing) {
    const other = teachers.find(existing.ownerId);
    throw conflict(
      existing.ownerId === teacherId
        ? `Псевдонім «${alias}» уже є в цього викладача.`
        : `Псевдонім «${alias}» уже належить викладачу «${other?.shortName ?? '—'}».`,
    );
  }
  const stored = teacherAliases.insert({ id: mockId('teacher-alias', crypto.randomUUID()), alias, ownerId: teacher.id });
  return { id: stored.id, alias: stored.alias };
}

function addDisciplineAlias(disciplineId: Uuid, rawAlias: unknown): AliasDto {
  const discipline = disciplines.get(disciplineId);
  const errors = new Errors();
  const alias = requiredText(errors, 'alias', rawAlias, 100, 'Вкажіть написання псевдоніма.');
  errors.throwIfAny();
  const owner = disciplines.all().find((d) => looseKey(d.shortName) === looseKey(alias));
  if (owner) {
    throw conflict(
      owner.id === disciplineId
        ? `«${alias}» — це вже скорочена назва цієї дисципліни.`
        : `«${alias}» — скорочена назва іншої дисципліни.`,
    );
  }
  const existing = disciplineAliases.all().find((a) => looseKey(a.alias) === looseKey(alias));
  if (existing) {
    const other = disciplines.find(existing.ownerId);
    throw conflict(
      existing.ownerId === disciplineId
        ? `Псевдонім «${alias}» уже є в цієї дисципліни.`
        : `Псевдонім «${alias}» уже належить дисципліні «${other?.shortName ?? '—'}».`,
    );
  }
  const stored = disciplineAliases.insert({ id: mockId('discipline-alias', crypto.randomUUID()), alias, ownerId: discipline.id });
  return { id: stored.id, alias: stored.alias };
}

/** Для імпорту: додає написання з файлу як псевдонім, якщо його ще немає. */
export function ensureAlias(type: 'TEACHER' | 'DISCIPLINE', ownerId: Uuid, alias: string): void {
  const collection = type === 'TEACHER' ? teacherAliases : disciplineAliases;
  if (collection.all().some((a) => looseKey(a.alias) === looseKey(alias))) return;
  if (type === 'TEACHER') addTeacherAlias(ownerId, alias);
  else addDisciplineAlias(ownerId, alias);
}

const aliasesOf = (collection: MockCollection<StoredAlias>, ownerId: Uuid): AliasDto[] =>
  collection
    .all()
    .filter((a) => a.ownerId === ownerId)
    .sort((a, b) => ukCollator.compare(a.alias, b.alias))
    .map(({ id, alias }) => ({ id, alias }));

function removeAlias(collection: MockCollection<StoredAlias>, ownerId: Uuid, aliasId: Uuid): void {
  const alias = collection.get(aliasId);
  if (alias.ownerId !== ownerId) throw problem({ status: 404, detail: `Псевдонім з id ${aliasId} не знайдено.` });
  collection.remove(aliasId);
}

// ───────────────────────── Компетенції ─────────────────────────

function competencesOf(teacherId: Uuid): DisciplineRef[] {
  teachers.get(teacherId);
  return competences
    .all()
    .filter((c) => c.teacherId === teacherId)
    .map((c) => disciplines.find(c.disciplineId))
    .filter((d): d is CatalogDiscipline => d !== undefined)
    .sort((a, b) => ukCollator.compare(a.shortName, b.shortName))
    .map(disciplineRefOf);
}

function replaceCompetences(teacherId: Uuid, disciplineIds: unknown): DisciplineRef[] {
  teachers.get(teacherId);
  if (!Array.isArray(disciplineIds) || disciplineIds.some((id) => typeof id !== 'string')) {
    throw badRequest('Перевірте список дисциплін.', [{ field: 'disciplineIds', message: 'Очікується список id дисциплін.' }]);
  }
  const unknown = (disciplineIds as string[]).find((id) => !disciplines.find(id));
  if (unknown) throw badRequest('Перевірте список дисциплін.', [{ field: 'disciplineIds', message: 'Дисципліну не знайдено.' }]);
  for (const item of competences.all().filter((c) => c.teacherId === teacherId)) competences.remove(item.id);
  for (const disciplineId of new Set(disciplineIds as string[])) {
    competences.insert({ id: mockId('competence', `${teacherId}|${disciplineId}`), teacherId, disciplineId });
  }
  return competencesOf(teacherId);
}

// ───────────────────────── Списки ─────────────────────────

function uuidParam(url: URL, name: string): string | undefined {
  return url.searchParams.get(name) || undefined;
}

function listOf(entity: CatalogEntity, url: URL): Page<AnyCatalogRow> {
  const q = url.searchParams.get('q');
  switch (entity) {
    case 'departments':
      return pageOf(
        sortBy(departments.all().filter((d) => matchesQuery(q, d.name, d.shortName)), url, { name: (d) => d.name, shortName: (d) => d.shortName }, 'name'),
        url,
      );
    case 'specialties': {
      const departmentId = uuidParam(url, 'departmentId');
      return pageOf(
        sortBy(
          specialties.all().filter((s) => matchesQuery(q, s.letters, s.name, s.code) && (!departmentId || s.departmentId === departmentId)),
          url,
          { letters: (s) => s.letters, name: (s) => s.name, code: (s) => s.code },
          'letters',
        ),
        url,
      );
    }
    case 'cycle-commissions':
      return pageOf(sortBy(cycleCommissions.all().filter((c) => matchesQuery(q, c.name)), url, { name: (c) => c.name }, 'name'), url);
    case 'buildings':
      return pageOf(sortBy(buildings.all().filter((b) => matchesQuery(q, b.name)), url, { name: (b) => b.name }, 'name'), url);
    case 'rooms': {
      const type = url.searchParams.get('type');
      const buildingId = uuidParam(url, 'buildingId');
      const active = boolParam(url, 'active');
      if (type && !ROOM_TYPES.includes(type as RoomType)) {
        throw badRequest('Невідомий тип приміщення.', [{ field: 'type', message: `Допустимі значення: ${ROOM_TYPES.join(', ')}.` }]);
      }
      const filtered = rooms
        .all()
        .filter(
          (r) =>
            matchesQuery(q, r.name) &&
            (!type || r.type === type) &&
            (!buildingId || r.building?.id === buildingId) &&
            (active === undefined || r.active === active),
        );
      return pageOf(
        sortBy(filtered, url, { name: (r) => r.name, type: (r) => r.type, capacity: (r) => r.capacity, active: (r) => r.active }, 'name'),
        url,
      );
    }
    case 'disciplines': {
      const active = boolParam(url, 'active');
      const needsReview = boolParam(url, 'needsReview');
      const aliasIndex = new Map<Uuid, string[]>();
      for (const alias of disciplineAliases.all()) aliasIndex.set(alias.ownerId, [...(aliasIndex.get(alias.ownerId) ?? []), alias.alias]);
      const filtered = disciplines
        .all()
        .filter(
          (d) =>
            matchesQuery(q, d.name, d.shortName, ...(aliasIndex.get(d.id) ?? [])) &&
            (active === undefined || d.active === active) &&
            (needsReview === undefined || d.needsReview === needsReview),
        );
      return pageOf(
        sortBy(
          filtered,
          url,
          { name: (d) => d.name, shortName: (d) => d.shortName, active: (d) => d.active, needsReview: (d) => d.needsReview },
          'shortName',
        ),
        url,
      );
    }
    case 'teachers': {
      const cycleCommissionId = uuidParam(url, 'cycleCommissionId');
      const active = boolParam(url, 'active');
      const needsReview = boolParam(url, 'needsReview');
      const linked = boolParam(url, 'linked');
      const aliasIndex = new Map<Uuid, string[]>();
      for (const alias of teacherAliases.all()) aliasIndex.set(alias.ownerId, [...(aliasIndex.get(alias.ownerId) ?? []), alias.alias]);
      const filtered = teachers
        .all()
        .filter(
          (t) =>
            matchesQuery(q, t.lastName, t.shortName, t.displayName, t.email, ...(aliasIndex.get(t.id) ?? [])) &&
            (!cycleCommissionId || t.cycleCommission?.id === cycleCommissionId) &&
            (active === undefined || t.active === active) &&
            (needsReview === undefined || t.needsReview === needsReview) &&
            (linked === undefined || t.linked === linked),
        );
      return pageOf(
        sortBy(
          filtered,
          url,
          {
            lastName: (t) => t.lastName,
            shortName: (t) => t.shortName,
            displayName: (t) => t.displayName,
            employment: (t) => t.employment,
            active: (t) => t.active,
          },
          'shortName',
        ),
        url,
      );
    }
    case 'groups': {
      const course = url.searchParams.get('course');
      const specialtyId = uuidParam(url, 'specialtyId');
      const shift = url.searchParams.get('shift');
      const active = boolParam(url, 'active');
      const canonical = q ? canonicalGroupCode(q) : null;
      const filtered = groups
        .all()
        .filter(
          (g) =>
            (matchesQuery(q, g.code, g.curator?.shortName) || (canonical !== null && g.code.startsWith(canonical))) &&
            (!course || g.course === Number(course)) &&
            (!specialtyId || g.specialtyId === specialtyId) &&
            (!shift || g.shift === Number(shift)) &&
            (active === undefined || g.active === active),
        );
      return pageOf(
        sortBy(
          filtered,
          url,
          { code: (g) => g.code, course: (g) => g.course, shift: (g) => g.shift, size: (g) => g.size, active: (g) => g.active },
          'code',
        ),
        url,
      );
    }
  }
}

function itemOf(entity: CatalogEntity, id: Uuid): AnyCatalogRow {
  switch (entity) {
    case 'departments':
      return departments.get(id);
    case 'specialties':
      return specialties.get(id);
    case 'cycle-commissions':
      return cycleCommissions.get(id);
    case 'buildings':
      return buildings.get(id);
    case 'rooms':
      return rooms.get(id);
    case 'disciplines':
      return disciplines.get(id);
    case 'teachers':
      return teachers.get(id);
    case 'groups':
      return groups.get(id);
  }
}

function save(entity: CatalogEntity, body: unknown, id?: Uuid): AnyCatalogRow {
  if (typeof body !== 'object' || body === null) throw badRequest('Тіло запиту має бути обʼєктом JSON.');
  switch (entity) {
    case 'departments':
      return saveDepartment(body as DepartmentRequest, id);
    case 'specialties':
      return saveSpecialty(body as SpecialtyRequest, id);
    case 'cycle-commissions':
      return saveNamed(cycleCommissions, 'cycle-commission', (name) => `Циклова комісія «${name}» уже існує.`, body as NameRequest, id);
    case 'buildings':
      return saveNamed(buildings, 'building', (name) => `Корпус «${name}» уже існує.`, body as NameRequest, id);
    case 'rooms':
      return saveRoom(body as RoomRequest, id);
    case 'disciplines':
      return saveDiscipline(body as DisciplineRequest, id);
    case 'teachers':
      return saveTeacher(body as TeacherRequest, id);
    case 'groups':
      return saveGroup(body as GroupRequest, id);
  }
}

// ───────────────────────── Обробники ─────────────────────────

const ENTITIES: readonly CatalogEntity[] = [
  'departments',
  'specialties',
  'cycle-commissions',
  'buildings',
  'rooms',
  'disciplines',
  'teachers',
  'groups',
];

const crudHandlers = ENTITIES.flatMap((entity) => [
  http.get(apiPath(`/admin/${entity}`), ({ request }) => HttpResponse.json(listOf(entity, new URL(request.url)))),
  http.get(apiPath(`/admin/${entity}/:id`), ({ params }) => HttpResponse.json(itemOf(entity, String(params.id)))),
  http.post(apiPath(`/admin/${entity}`), async ({ request }) => {
    const created = save(entity, await readJson<unknown>(request));
    return HttpResponse.json(created, { status: 201 });
  }),
  http.put(apiPath(`/admin/${entity}/:id`), async ({ request, params }) => {
    const id = String(params.id);
    itemOf(entity, id);
    return HttpResponse.json(save(entity, await readJson<unknown>(request), id));
  }),
  http.delete(apiPath(`/admin/${entity}/:id`), ({ params }) => {
    deleteOrConflict(entity, String(params.id));
    return new HttpResponse(null, { status: 204 });
  }),
]);

export const handlers = [
  ...crudHandlers,

  // Псевдоніми
  http.get(apiPath('/admin/teachers/:id/aliases'), ({ params }) => {
    teachers.get(String(params.id));
    return HttpResponse.json(aliasesOf(teacherAliases, String(params.id)));
  }),
  http.post(apiPath('/admin/teachers/:id/aliases'), async ({ request, params }) => {
    const body = await readJson<{ alias?: unknown }>(request);
    return HttpResponse.json(addTeacherAlias(String(params.id), body.alias), { status: 201 });
  }),
  http.delete(apiPath('/admin/teachers/:id/aliases/:aliasId'), ({ params }) => {
    teachers.get(String(params.id));
    removeAlias(teacherAliases, String(params.id), String(params.aliasId));
    return new HttpResponse(null, { status: 204 });
  }),
  http.get(apiPath('/admin/disciplines/:id/aliases'), ({ params }) => {
    disciplines.get(String(params.id));
    return HttpResponse.json(aliasesOf(disciplineAliases, String(params.id)));
  }),
  http.post(apiPath('/admin/disciplines/:id/aliases'), async ({ request, params }) => {
    const body = await readJson<{ alias?: unknown }>(request);
    return HttpResponse.json(addDisciplineAlias(String(params.id), body.alias), { status: 201 });
  }),
  http.delete(apiPath('/admin/disciplines/:id/aliases/:aliasId'), ({ params }) => {
    disciplines.get(String(params.id));
    removeAlias(disciplineAliases, String(params.id), String(params.aliasId));
    return new HttpResponse(null, { status: 204 });
  }),

  // Компетенції викладача
  http.get(apiPath('/admin/teachers/:id/disciplines'), ({ params }) => HttpResponse.json(competencesOf(String(params.id)))),
  http.put(apiPath('/admin/teachers/:id/disciplines'), async ({ request, params }) => {
    const body = await readJson<{ disciplineIds?: unknown }>(request);
    return HttpResponse.json(replaceCompetences(String(params.id), body.disciplineIds));
  }),

  // Підгрупи
  http.get(apiPath('/admin/groups/:id/subgroups'), ({ params }) => HttpResponse.json(groups.get(String(params.id)).subgroups)),
  http.post(apiPath('/admin/groups/:id/subgroups'), async ({ request, params }) => {
    const body = await readJson<SubgroupRequest>(request);
    return HttpResponse.json(saveSubgroup(String(params.id), body), { status: 201 });
  }),
  http.put(apiPath('/admin/groups/:id/subgroups/:subgroupId'), async ({ request, params }) => {
    const body = await readJson<SubgroupRequest>(request);
    return HttpResponse.json(saveSubgroup(String(params.id), body, String(params.subgroupId)));
  }),
  http.delete(apiPath('/admin/groups/:id/subgroups/:subgroupId'), ({ params }) => {
    const groupId = String(params.id);
    const subgroupId = String(params.subgroupId);
    const group = groups.get(groupId);
    const subgroup = group.subgroups.find((s) => s.id === subgroupId);
    if (!subgroup) throw problem({ status: 404, detail: `Підгрупу з id ${subgroupId} не знайдено.` });
    const usage = subgroupUsage(group, subgroup.number);
    if (usage) throw conflict(`Підгрупу ${subgroup.number} групи ${group.code} використано: ${usage}.`);
    groups.update(groupId, (current) => ({ ...current, subgroups: current.subgroups.filter((s) => s.id !== subgroupId) }));
    return new HttpResponse(null, { status: 204 });
  }),
];

/** Підпис «спеціальність» у списках: Ref з літерами, як у core. */
export function specialtyRefOf(specialty: CatalogSpecialty): Ref {
  return { id: specialty.id, label: specialty.letters };
}
