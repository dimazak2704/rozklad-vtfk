/**
 * Каталог (CONTRACTS §2 «Catalog»): довідники диспетчера — відділення, спеціальності, циклові комісії,
 * корпуси, аудиторії, дисципліни (+ псевдоніми), викладачі (+ псевдоніми, компетенції), групи (+ підгрупи).
 *
 * Контракт описує лише шляхи й «CRUD зі сторінками». Поля рядків і тіла запитів нижче уточнюють
 * @inferred-типи з types.ts за API core (catalog/web, *Dtos): кожен запис має `version` (оптимістичне
 * блокування — PUT з іншою версією дає 409 «Дані змінилися»), видалення використаного запису — 409 з
 * поясненням українською.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, type QueryParams } from './client';
import { qk } from './queryKeys';
import type {
  AliasDto,
  BuildingDto,
  CycleCommissionDto,
  DepartmentDto,
  DisciplineDto,
  DisciplineRef,
  Employment,
  GroupDto,
  Page,
  Ref,
  RoomDto,
  RoomType,
  ShiftNumber,
  SubgroupDto,
  TeacherDto,
  Uuid,
} from './types';

// ───────────────────────── Рядки довідників ─────────────────────────

interface Versioned {
  /** Версія запису: передайте її в PUT, щоб не перезаписати чужі зміни. */
  version: number;
}

export type CatalogDepartment = DepartmentDto & Versioned;
export type CatalogCycleCommission = CycleCommissionDto & Versioned;
export type CatalogBuilding = BuildingDto & Versioned;
export type CatalogRoom = Omit<RoomDto, 'version'> & Versioned;
export type CatalogDiscipline = Omit<DisciplineDto, 'version'> & Versioned;

/** Спеціальність: код класифікатора в імпортованих даних буває порожнім (CHANGELOG-CONTRACTS, nullable). */
export interface CatalogSpecialty extends Versioned {
  id: Uuid;
  code: string | null;
  /** Літери в кодах груп: «КІ». */
  letters: string;
  name: string;
  departmentId: Uuid | null;
  department: Ref | null;
}

export interface CatalogTeacher extends Omit<TeacherDto, 'linked' | 'version'>, Versioned {
  /** Чи є в системі обліковий запис (зареєструвався за запрошенням). */
  linked: boolean;
}

export type CatalogSubgroup = SubgroupDto & Versioned;

export interface CatalogGroup extends Omit<GroupDto, 'subgroups' | 'version'>, Versioned {
  specialtyId: Uuid | null;
  /** Підгрупи 1..n; 0 — уся група, вона не зберігається. */
  subgroups: CatalogSubgroup[];
}

// ───────────────────────── Тіла запитів ─────────────────────────

export interface DepartmentRequest {
  name: string;
  shortName?: string | null;
  version?: number;
}

export interface SpecialtyRequest {
  /** Три цифри класифікатора: «123». */
  code?: string | null;
  letters: string;
  name: string;
  departmentId?: Uuid | null;
  version?: number;
}

/** Циклові комісії й корпуси мають лише назву. */
export interface NameRequest {
  name: string;
  version?: number;
}

export interface RoomRequest {
  buildingId?: Uuid | null;
  name: string;
  type: RoomType;
  /** null — місткість невідома (перевірку місткості пропущено). */
  capacity?: number | null;
  /** Скільки груп одночасно: спортзал «с.з.» приймає кілька. */
  maxParallel?: number | null;
  active?: boolean;
  version?: number;
}

export interface DisciplineRequest {
  name: string;
  shortName: string;
  /** `#RRGGBB` або null — тоді колір лінії виводиться з id. */
  color?: string | null;
  active?: boolean;
  needsReview?: boolean;
  version?: number;
}

export interface TeacherRequest {
  lastName: string;
  firstName?: string | null;
  middleName?: string | null;
  /** «Кренцін М.Д.»; сервер складе з імені, якщо порожньо. */
  shortName?: string | null;
  /** Публічне ім'я; прізвище, якщо порожньо. */
  displayName?: string | null;
  cycleCommissionId?: Uuid | null;
  employment?: Employment;
  email?: string | null;
  active?: boolean;
  needsReview?: boolean;
  version?: number;
}

/** Курс, спеціальність і номер беруться з коду («4КІ1»); якщо їх надіслати, вони мають збігатися з кодом. */
export interface GroupRequest {
  code: string;
  course?: number | null;
  specialtyId?: Uuid | null;
  number?: number | null;
  size?: number | null;
  shift?: ShiftNumber | null;
  curatorTeacherId?: Uuid | null;
  homeRoomId?: Uuid | null;
  active?: boolean;
  version?: number;
}

export interface SubgroupRequest {
  number: number;
  name?: string | null;
  size?: number | null;
  version?: number;
}

// ───────────────────────── Відповідність «довідник → типи» ─────────────────────────

export interface CatalogSchema {
  departments: { row: CatalogDepartment; request: DepartmentRequest };
  specialties: { row: CatalogSpecialty; request: SpecialtyRequest };
  'cycle-commissions': { row: CatalogCycleCommission; request: NameRequest };
  buildings: { row: CatalogBuilding; request: NameRequest };
  rooms: { row: CatalogRoom; request: RoomRequest };
  disciplines: { row: CatalogDiscipline; request: DisciplineRequest };
  teachers: { row: CatalogTeacher; request: TeacherRequest };
  groups: { row: CatalogGroup; request: GroupRequest };
}

export type CatalogEntity = keyof CatalogSchema;
export type CatalogRow<E extends CatalogEntity> = CatalogSchema[E]['row'];
export type CatalogRequest<E extends CatalogEntity> = CatalogSchema[E]['request'];

/** Довідники з псевдонімами (написання з Excel і розпоряджень). */
export type AliasOwner = 'teachers' | 'disciplines';

/**
 * Параметри списку: `q`, фільтри довідника, `page` з нуля, `size` до 200, `sort` = `поле,asc|desc`.
 * Поля сортування — як у core: rooms `name|type|capacity|active`, teachers `lastName|shortName|…` тощо.
 */
export type CatalogListParams = Record<string, string | number | boolean | undefined>;

/** Найбільша сторінка, яку віддає core (PageQuery.MAX_SIZE). */
export const CATALOG_MAX_PAGE_SIZE = 200;

const path = (entity: CatalogEntity, id?: Uuid) => (id ? `/admin/${entity}/${id}` : `/admin/${entity}`);

function toQuery(params: CatalogListParams): QueryParams {
  const out: QueryParams = {};
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') out[key] = value;
  return out;
}

/**
 * Що застаріває після зміни довідника: сам каталог, навантаження (показує назви дисциплін і викладачів)
 * і публічні списки груп, викладачів, аудиторій.
 */
function invalidateCatalog(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: qk.admin.catalog.all });
  void queryClient.invalidateQueries({ queryKey: qk.admin.load() });
  void queryClient.invalidateQueries({ queryKey: qk.public.all });
}

// ───────────────────────── Списки й записи ─────────────────────────

/** Сторінка довідника з пошуком, фільтрами й сортуванням (попередні дані лишаються на екрані під час запиту). */
export function useCatalogList<E extends CatalogEntity>(entity: E, params: CatalogListParams, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: qk.admin.catalog.list(entity, params),
    queryFn: ({ signal }) => api.get<Page<CatalogRow<E>>>(path(entity), toQuery(params), { signal }),
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  });
}

/**
 * Увесь довідник для полів вибору (куратор, аудиторія, дисципліна…): одна сторінка максимального розміру.
 * Якщо записів більше за сторінку, `truncated` = true — інтерфейс має сказати про це, а не мовчки обрізати.
 */
export function useCatalogOptions<E extends CatalogEntity>(entity: E, params: CatalogListParams = {}, options: { enabled?: boolean } = {}) {
  const query = useQuery({
    queryKey: qk.admin.catalog.list(entity, { ...params, size: CATALOG_MAX_PAGE_SIZE, purpose: 'options' }),
    queryFn: ({ signal }) =>
      api.get<Page<CatalogRow<E>>>(path(entity), toQuery({ ...params, page: 0, size: CATALOG_MAX_PAGE_SIZE }), { signal }),
    staleTime: 5 * 60_000,
    enabled: options.enabled ?? true,
  });
  return {
    ...query,
    items: query.data?.items,
    truncated: query.data ? query.data.total > query.data.items.length : false,
  };
}

export function useCatalogItem<E extends CatalogEntity>(entity: E, id: Uuid | null | undefined) {
  return useQuery({
    queryKey: qk.admin.catalog.detail(entity, id ?? ''),
    queryFn: ({ signal }) => api.get<CatalogRow<E>>(path(entity, id ?? ''), undefined, { signal }),
    enabled: Boolean(id),
  });
}

export interface SaveCatalogInput<E extends CatalogEntity> {
  /** Немає id — створення (POST), є — оновлення (PUT). */
  id?: Uuid | null;
  body: CatalogRequest<E>;
}

/** Створення або оновлення запису. Помилки полів (400) і конфлікти (409) обробляє форма. */
export function useSaveCatalogItem<E extends CatalogEntity>(entity: E) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: SaveCatalogInput<E>) =>
      id ? api.put<CatalogRow<E>>(path(entity, id), body) : api.post<CatalogRow<E>>(path(entity), body),
    onSuccess: () => invalidateCatalog(queryClient),
  });
}

/** Видалення. Використаний запис сервер не видаляє: 409 з поясненням, що його тримає. */
export function useDeleteCatalogItem(entity: CatalogEntity) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(path(entity, id)),
    onSuccess: () => invalidateCatalog(queryClient),
  });
}

// ───────────────────────── Псевдоніми ─────────────────────────

const aliasesKey = (owner: AliasOwner, id: Uuid) => [...qk.admin.catalog.detail(owner, id), 'aliases'] as const;

/** Псевдоніми викладача чи дисципліни: як їх пишуть у таблицях і розпорядженнях («Граболінська», «Інфом.»). */
export function useAliases(owner: AliasOwner, id: Uuid | null | undefined) {
  return useQuery({
    queryKey: aliasesKey(owner, id ?? ''),
    queryFn: ({ signal }) => api.get<AliasDto[]>(`${path(owner, id ?? '')}/aliases`, undefined, { signal }),
    enabled: Boolean(id),
  });
}

export function useAddAlias(owner: AliasOwner, id: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (alias: string) => api.post<AliasDto>(`${path(owner, id)}/aliases`, { alias }),
    onSuccess: () => invalidateCatalog(queryClient),
  });
}

export function useRemoveAlias(owner: AliasOwner, id: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (aliasId: Uuid) => api.delete(`${path(owner, id)}/aliases/${aliasId}`),
    onSuccess: () => invalidateCatalog(queryClient),
  });
}

// ───────────────────────── Компетенції викладача ─────────────────────────

const competencesKey = (teacherId: Uuid) => [...qk.admin.catalog.detail('teachers', teacherId), 'disciplines'] as const;

/** Дисципліни, які викладач може вести: перший критерій ранжування кандидатів на заміну (DOMAIN §7). */
export function useTeacherDisciplines(teacherId: Uuid | null | undefined) {
  return useQuery({
    queryKey: competencesKey(teacherId ?? ''),
    queryFn: ({ signal }) => api.get<DisciplineRef[]>(`${path('teachers', teacherId ?? '')}/disciplines`, undefined, { signal }),
    enabled: Boolean(teacherId),
  });
}

/** Замінює весь список компетенцій (PUT), відповідь — новий список. */
export function useSetTeacherDisciplines(teacherId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (disciplineIds: Uuid[]) =>
      api.put<DisciplineRef[]>(`${path('teachers', teacherId)}/disciplines`, { disciplineIds }),
    onSuccess: (list) => {
      queryClient.setQueryData(competencesKey(teacherId), list);
      invalidateCatalog(queryClient);
    },
  });
}

// ───────────────────────── Підгрупи ─────────────────────────

const subgroupsKey = (groupId: Uuid) => [...qk.admin.catalog.detail('groups', groupId), 'subgroups'] as const;

export function useSubgroups(groupId: Uuid | null | undefined) {
  return useQuery({
    queryKey: subgroupsKey(groupId ?? ''),
    queryFn: ({ signal }) => api.get<CatalogSubgroup[]>(`${path('groups', groupId ?? '')}/subgroups`, undefined, { signal }),
    enabled: Boolean(groupId),
  });
}

export interface SaveSubgroupInput {
  subgroupId?: Uuid | null;
  body: SubgroupRequest;
}

export function useSaveSubgroup(groupId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ subgroupId, body }: SaveSubgroupInput) =>
      subgroupId
        ? api.put<CatalogSubgroup>(`${path('groups', groupId)}/subgroups/${subgroupId}`, body)
        : api.post<CatalogSubgroup>(`${path('groups', groupId)}/subgroups`, body),
    onSuccess: () => invalidateCatalog(queryClient),
  });
}

/** Підгрупу, яку використано в розкладі чи навантаженні, сервер не видаляє (409 з переліком). */
export function useDeleteSubgroup(groupId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (subgroupId: Uuid) => api.delete(`${path('groups', groupId)}/subgroups/${subgroupId}`),
    onSuccess: () => invalidateCatalog(queryClient),
  });
}
