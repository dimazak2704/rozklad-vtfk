import { describe, expect, it } from 'vitest';
import type { CatalogDiscipline, CatalogGroup, CatalogRoom, CatalogSubgroup, CatalogTeacher } from '@/api/catalog';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { AliasDto, DisciplineRef, Page } from '@/api/types';
import { GROUPS, ROOMS } from '../fixtures';

/** Очікувана помилка API (тест падає, якщо запит пройшов). */
async function failure(request: Promise<unknown>): Promise<ApiError> {
  try {
    await request;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Запит мав завершитися помилкою');
}

const rooms = (query: Record<string, string | number | boolean>) => api.get<Page<CatalogRoom>>('/admin/rooms', query);
const teacherNamed = async (q: string) => (await api.get<Page<CatalogTeacher>>('/admin/teachers', { q })).items[0] as CatalogTeacher;

describe('GET /api/admin/rooms', () => {
  it('сторінка за назвою з природним порядком чисел', async () => {
    const page = await rooms({ size: 5 });
    expect(page).toMatchObject({ page: 0, size: 5, total: ROOMS.length });
    expect(page.items.map((r) => r.name)).toEqual(['1', '2', '3', '6', '8']);
  });

  it('фільтрує за типом і шукає за назвою', async () => {
    expect((await rooms({ type: 'GYM' })).items.map((r) => r.name)).toEqual(['с.з.']);
    expect((await rooms({ q: '40' })).items.every((r) => r.name.includes('40'))).toBe(true);
  });

  it('сортування й розмір сторінки перевіряє, як core', async () => {
    expect((await rooms({ sort: 'name,desc', size: 1 })).items[0]?.name).toBe('с.п.');
    expect((await failure(rooms({ sort: 'floor,asc' }))).errors[0]?.field).toBe('sort');
    expect((await failure(rooms({ size: 500 }))).errors[0]).toEqual({ field: 'size', message: 'Розмір сторінки має бути цілим числом від 1 до 200.' });
  });
});

describe('POST/PUT/DELETE /api/admin/rooms', () => {
  it('порожня назва — 400 з помилкою поля', async () => {
    const error = await failure(api.post('/admin/rooms', { name: '  ', type: 'CLASSROOM' }));
    expect(error.status).toBe(400);
    expect(error.fieldErrors()).toEqual({ name: 'Вкажіть назву або номер аудиторії.' });
  });

  it('назва з самих знаків — помилка поля від сервера', async () => {
    const error = await failure(api.post('/admin/rooms', { name: '—', type: 'CLASSROOM' }));
    expect(error.fieldErrors().name).toBe('Назва аудиторії має містити літери або цифри.');
  });

  it('дублікат без урахування регістру й крапок — 409', async () => {
    const error = await failure(api.post('/admin/rooms', { name: 'С.З.', type: 'GYM' }));
    expect(error).toMatchObject({ status: 409, message: 'Аудиторія «с.з.» уже існує.' });
  });

  it('створює, знаходить і видаляє нову аудиторію', async () => {
    const created = await api.post<CatalogRoom>('/admin/rooms', { name: '512', type: 'COMPUTER_LAB', capacity: 30 });
    expect(created).toMatchObject({ name: '512', type: 'COMPUTER_LAB', capacity: 30, maxParallel: 1, active: true, version: 0 });
    expect((await rooms({ q: '512' })).items).toHaveLength(1);
    await api.delete(`/admin/rooms/${created.id}`);
    expect((await rooms({ q: '512' })).total).toBe(0);
  });

  it('аудиторію з розкладу не видаляє: 409 з поясненням і порадою', async () => {
    const room = (await rooms({ q: '301' })).items.find((r) => r.name === '301') as CatalogRoom;
    const error = await failure(api.delete(`/admin/rooms/${room.id}`));
    expect(error.status).toBe(409);
    expect(error.message).toBe(
      'Аудиторію «301» не можна видалити: вона є в розкладі, навантаженні або закріплена за групою. Позначте її неактивною.',
    );
  });

  it('застаріла версія — 409 «Дані змінилися», актуальна — зберігає й збільшує версію', async () => {
    const room = (await rooms({ q: '301' })).items.find((r) => r.name === '301') as CatalogRoom;
    const stale = await failure(api.put(`/admin/rooms/${room.id}`, { name: '301', type: 'CLASSROOM', version: 7 }));
    expect(stale).toMatchObject({ status: 409, title: 'Дані змінилися', type: 'urn:rozklad:problem:stale-data' });
    const saved = await api.put<CatalogRoom>(`/admin/rooms/${room.id}`, { name: '301', type: 'CLASSROOM', capacity: 32, version: room.version });
    expect(saved).toMatchObject({ capacity: 32, version: room.version + 1 });
  });
});

describe('/api/admin/groups', () => {
  it('код латиницею нормалізує, курс і спеціальність бере з коду', async () => {
    const created = await api.post<CatalogGroup>('/admin/groups', { code: '4ki4' });
    expect(created).toMatchObject({ code: '4КІ4', course: 4, number: 4, shift: 1, specialty: { label: 'КІ' }, subgroups: [] });
  });

  it('другий курс за замовчуванням — друга зміна', async () => {
    expect((await api.post<CatalogGroup>('/admin/groups', { code: '2КІ4' })).shift).toBe(2);
  });

  it('пояснює помилки коду', async () => {
    expect((await failure(api.post('/admin/groups', { code: 'КІ-41' }))).fieldErrors().code).toBe(
      'Код групи має вигляд «4КІ1»: курс, літери спеціальності, номер.',
    );
    expect((await failure(api.post('/admin/groups', { code: '4ЮЮ1' }))).fieldErrors().code).toBe(
      'Спеціальність «ЮЮ» не знайдено: спершу додайте її до довідника.',
    );
    expect((await failure(api.post('/admin/groups', { code: '4КІ1' }))).message).toBe('Група 4КІ1 уже існує.');
  });

  it('підгрупу із заняттями не видаляє', async () => {
    const group = GROUPS.find((g) => g.code === '1ТК1') as CatalogGroup;
    const subgroups = await api.get<CatalogSubgroup[]>(`/admin/groups/${group.id}/subgroups`);
    const first = subgroups.find((s) => s.number === 1) as CatalogSubgroup;
    const error = await failure(api.delete(`/admin/groups/${group.id}/subgroups/${first.id}`));
    expect(error.status).toBe(409);
    expect(error.message).toMatch(/^Підгрупу 1 групи 1ТК1 використано: \d+\s(заняття|занять) у розкладі; навантаження\.$/);
  });
});

describe('псевдоніми й компетенції', () => {
  it('додає написання з таблиці як псевдонім, і пошук знаходить викладача за ним', async () => {
    const teacher = await teacherNamed('Гарболінська');
    const alias = await api.post<AliasDto>(`/admin/teachers/${teacher.id}/aliases`, { alias: 'Граболінська' });
    expect(alias.alias).toBe('Граболінська');
    expect((await api.get<Page<CatalogTeacher>>('/admin/teachers', { q: 'Грабол' })).items.map((t) => t.shortName)).toEqual(['Гарболінська']);
    expect((await failure(api.post(`/admin/teachers/${teacher.id}/aliases`, { alias: 'граболінська' }))).message).toBe(
      'Псевдонім «граболінська» уже є в цього викладача.',
    );
  });

  it('не дає зробити псевдонімом імʼя іншого викладача', async () => {
    const teacher = await teacherNamed('Кренцін');
    expect((await failure(api.post(`/admin/teachers/${teacher.id}/aliases`, { alias: 'Непийвода' }))).message).toBe(
      '«Непийвода» — основне ім\'я іншого викладача.',
    );
  });

  it('псевдонім дисципліни з DOMAIN §10 уже є: «НДКС» = НДЕКСМ', async () => {
    const discipline = (await api.get<Page<CatalogDiscipline>>('/admin/disciplines', { q: 'НДКС' })).items[0] as CatalogDiscipline;
    expect(discipline.shortName).toBe('НДЕКСМ');
    expect((await api.get<AliasDto[]>(`/admin/disciplines/${discipline.id}/aliases`)).map((a) => a.alias)).toEqual(['НДКС']);
  });

  it('компетенції — з розкладу; PUT замінює список повністю', async () => {
    const teacher = await teacherNamed('Кренцін');
    const current = await api.get<DisciplineRef[]>(`/admin/teachers/${teacher.id}/disciplines`);
    expect(current.map((d) => d.shortName)).toContain('СТП');
    const stp = current.find((d) => d.shortName === 'СТП') as DisciplineRef;
    expect(await api.put<DisciplineRef[]>(`/admin/teachers/${teacher.id}/disciplines`, { disciplineIds: [stp.id] })).toEqual([stp]);
    expect((await failure(api.put(`/admin/teachers/${teacher.id}/disciplines`, { disciplineIds: ['невідома'] }))).fieldErrors()).toEqual({
      disciplineIds: 'Дисципліну не знайдено.',
    });
  });
});

describe('структура коледжу', () => {
  it('відділення зі спеціальностями не видаляє, порожній корпус — так', async () => {
    const departments = await api.get<Page<{ id: string; name: string }>>('/admin/departments');
    const computer = departments.items.find((d) => d.name === 'Компʼютерне відділення') as { id: string };
    expect((await failure(api.delete(`/admin/departments/${computer.id}`))).message).toBe(
      'Відділення «Компʼютерне відділення» не можна видалити: до нього належать спеціальності.',
    );
    const building = await api.post<{ id: string; name: string }>('/admin/buildings', { name: 'Головний корпус' });
    await api.delete(`/admin/buildings/${building.id}`);
    expect((await api.get<Page<unknown>>('/admin/buildings')).total).toBe(0);
  });

  it('літери спеціальності латиницею перетворює на кирилицю', async () => {
    const created = await api.post<{ letters: string; code: string | null }>('/admin/specialties', { letters: 'kc', name: 'Кібербезпека', code: '125' });
    expect(created).toMatchObject({ letters: 'КС', code: '125' });
    expect((await failure(api.post('/admin/specialties', { letters: 'КІ', name: 'Дубль' }))).message).toBe('Спеціальність з літерами «КІ» уже існує.');
  });
});
