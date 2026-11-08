/**
 * Область «Базовий розклад» (CONTRACTS §2 Timetable): версії, заняття шаблону, конфлікти, метрики,
 * порівняння версій і публікація. Хуки TanStack Query; ключі — лише `qk.admin.timetable`.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import { isApiError } from './errors';
import { usePublicGroups, usePublicRooms, usePublicTeachers } from './public';
import { qk } from './queryKeys';
import type {
  Conflict,
  ConflictKind,
  CreateVersionRequest,
  DisciplineDto,
  MetricsDto,
  MoveLessonRequest,
  Page,
  PublicGroupDto,
  PublicRoomDto,
  PublicTeacherDto,
  PublishVersionResponse,
  TemplateLessonDto,
  TemplateLessonRequest,
  TimetableVersionDto,
  Uuid,
  VersionDiffDto,
} from './types';

// ───────────────────────── Типи області (доповнюють types.ts) ─────────────────────────

/** Мʼякі види конфліктів (CHANGELOG-CONTRACTS, Foundation: `Conflict.kind`). */
export type SoftConflictKind = 'WINDOW' | 'BROKEN_DAY' | 'DAY_OVERLOAD' | 'DISCIPLINE_REPEAT' | 'UNWANTED';
export type TimetableConflictKind = ConflictKind | SoftConflictKind;

/** Конфлікт розкладу з повним переліком видів (жорсткі з CONTRACTS + мʼякі). */
export interface TimetableConflict extends Omit<Conflict, 'kind'> {
  kind: TimetableConflictKind;
}

/** GET …/versions/{id}/conflicts з мʼякими видами. */
export interface TimetableConflictsDto {
  hard: TimetableConflict[];
  soft: TimetableConflict[];
}

/**
 * Відповідь на POST/PUT заняття і на move (у т.ч. `?dryRun=true`): заняття (для dryRun — яким воно стало б)
 * і конфлікти зачепленого слота. Для move контракт каже лише «200 або 409 conflicts». @inferred
 */
export interface LessonMutationResult {
  lesson: TemplateLessonDto;
  conflicts: TimetableConflict[];
}

/** Результат перевірки переміщення: `ok` — можна (можливо, з мʼякими попередженнями), `blocked` — 409. */
export type MovePreview =
  | { status: 'ok'; conflicts: TimetableConflict[] }
  | { status: 'blocked'; conflicts: TimetableConflict[]; message: string };

/** Що змінюється при переміщенні; `room` — лише для миттєвого показу в кеші до відповіді сервера. */
export interface MoveLessonVariables {
  lessonId: Uuid;
  target: MoveLessonRequest;
  room?: TemplateLessonDto['room'];
}

/** Довідники для редактора: групи (курс, відділення, зміна), викладачі, аудиторії, дисципліни. */
export interface PlanningCatalog {
  groups: PublicGroupDto[];
  teachers: PublicTeacherDto[];
  rooms: PublicRoomDto[];
  disciplines: DisciplineDto[];
}

// ───────────────────────── Шляхи ─────────────────────────

const versionsPath = '/admin/timetable/versions';
const versionPath = (versionId: Uuid) => `${versionsPath}/${encodeURIComponent(versionId)}`;
const lessonPath = (versionId: Uuid, lessonId: Uuid) => `${versionPath(versionId)}/lessons/${encodeURIComponent(lessonId)}`;

// ───────────────────────── Довідники ─────────────────────────

const CATALOG_STALE_MS = 10 * 60_000;
const DISCIPLINE_PAGE_SIZE = 100;

/** Усі дисципліни каталогу: список сторінковий, тож читаємо сторінки, доки не зберемо `total`. */
export async function fetchAllDisciplines(signal?: AbortSignal): Promise<DisciplineDto[]> {
  const all: DisciplineDto[] = [];
  for (let page = 0; ; page += 1) {
    const chunk = await api.get<Page<DisciplineDto>>(
      '/admin/disciplines',
      { page, size: DISCIPLINE_PAGE_SIZE, sort: 'shortName,asc' },
      { signal },
    );
    all.push(...chunk.items);
    if (chunk.items.length === 0 || all.length >= chunk.total) return all;
  }
}

export function usePlanningCatalog() {
  const groups = usePublicGroups();
  const teachers = usePublicTeachers();
  const rooms = usePublicRooms();
  const disciplines = useQuery({
    queryKey: qk.admin.catalog.list('disciplines', { all: true }),
    queryFn: ({ signal }) => fetchAllDisciplines(signal),
    staleTime: CATALOG_STALE_MS,
  });
  const queries = [groups, teachers, rooms, disciplines];
  const data: PlanningCatalog | undefined =
    groups.data && teachers.data && rooms.data && disciplines.data
      ? { groups: groups.data, teachers: teachers.data, rooms: rooms.data, disciplines: disciplines.data }
      : undefined;
  return {
    data,
    isPending: queries.some((query) => query.isPending),
    error: queries.find((query) => query.error)?.error ?? null,
    refetch: () => {
      for (const query of queries) if (query.error) void query.refetch();
    },
  };
}

// ───────────────────────── Запити ─────────────────────────

export function useTimetableVersions(semesterId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.admin.timetable.versions(semesterId),
    queryFn: ({ signal }) => api.get<TimetableVersionDto[]>(versionsPath, { semesterId }, { signal }),
    enabled: Boolean(semesterId),
  });
}

export function useVersionLessons(versionId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.admin.timetable.lessons(versionId ?? '', {}),
    queryFn: ({ signal }) => api.get<TemplateLessonDto[]>(`${versionPath(versionId ?? '')}/lessons`, undefined, { signal }),
    enabled: Boolean(versionId),
  });
}

export function useVersionConflicts(versionId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.admin.timetable.conflicts(versionId ?? ''),
    queryFn: ({ signal }) => api.get<TimetableConflictsDto>(`${versionPath(versionId ?? '')}/conflicts`, undefined, { signal }),
    enabled: Boolean(versionId),
  });
}

export function useVersionMetrics(versionId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.admin.timetable.metrics(versionId ?? ''),
    queryFn: ({ signal }) => api.get<MetricsDto>(`${versionPath(versionId ?? '')}/metrics`, undefined, { signal }),
    enabled: Boolean(versionId),
  });
}

/** Різниця `base → target`: що додано, прибрано й переміщено в `target` відносно `base`. */
export function useVersionDiff(baseId: Uuid | undefined, targetId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.admin.timetable.diff(baseId ?? '', targetId ?? ''),
    queryFn: ({ signal }) =>
      api.get<VersionDiffDto>(`${versionPath(baseId ?? '')}/diff/${encodeURIComponent(targetId ?? '')}`, undefined, { signal }),
    enabled: Boolean(baseId && targetId && baseId !== targetId),
  });
}

// ───────────────────────── Інвалідація ─────────────────────────

/**
 * Після правки заняття застаріли конфлікти, метрики, лічильники версій і порівняння.
 * Самі заняття вже оновлені в кеші відповіддю сервера — їх (≈1000 рядків) не перечитуємо.
 */
export function invalidateVersionDerived(queryClient: QueryClient, versionId: Uuid): Promise<void> {
  return queryClient.invalidateQueries({
    queryKey: qk.admin.timetable.all,
    predicate: (query) => {
      const [, , kind, id] = query.queryKey;
      if (kind === 'lessons') return false;
      if (kind === 'conflicts' || kind === 'metrics') return id === versionId;
      return true;
    },
  });
}

function patchLessons(
  queryClient: QueryClient,
  versionId: Uuid,
  recipe: (lessons: TemplateLessonDto[]) => TemplateLessonDto[],
): TemplateLessonDto[] | undefined {
  const key = qk.admin.timetable.lessons(versionId, {});
  const previous = queryClient.getQueryData<TemplateLessonDto[]>(key);
  if (previous) queryClient.setQueryData<TemplateLessonDto[]>(key, recipe(previous));
  return previous;
}

function restoreLessons(queryClient: QueryClient, versionId: Uuid, previous: TemplateLessonDto[] | undefined): void {
  if (previous) queryClient.setQueryData(qk.admin.timetable.lessons(versionId, {}), previous);
}

/** Заняття потоку переміщуються разом (одна пара для кількох груп): у кеші рухаємо всіх учасників. */
export function applyMoveToLessons(lessons: readonly TemplateLessonDto[], variables: MoveLessonVariables): TemplateLessonDto[] {
  const moved = lessons.find((lesson) => lesson.id === variables.lessonId);
  if (!moved) return [...lessons];
  const streamId = moved.streamId ?? null;
  return lessons.map((lesson) => {
    const isTarget = lesson.id === moved.id || (streamId !== null && lesson.streamId === streamId);
    if (!isTarget) return lesson;
    return {
      ...lesson,
      weekday: variables.target.weekday,
      pair: variables.target.pair,
      parity: variables.target.parity,
      ...(variables.target.roomId !== undefined && variables.room !== undefined ? { room: variables.room } : {}),
    };
  });
}

// ───────────────────────── Мутації занять ─────────────────────────

export function useMoveLesson(versionId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lessonId, target }: MoveLessonVariables) =>
      api.post<LessonMutationResult>(`${lessonPath(versionId, lessonId)}/move`, target),
    onMutate: async (variables) => {
      await queryClient.cancelQueries({ queryKey: qk.admin.timetable.lessons(versionId, {}) });
      return { previous: patchLessons(queryClient, versionId, (lessons) => applyMoveToLessons(lessons, variables)) };
    },
    onError: (_error, _variables, context) => restoreLessons(queryClient, versionId, context?.previous),
    onSuccess: (result) => {
      if (result.lesson.streamId) {
        // інші учасники потоку теж переїхали — беремо їх із сервера
        void queryClient.invalidateQueries({ queryKey: qk.admin.timetable.lessons(versionId, {}) });
      } else {
        patchLessons(queryClient, versionId, (lessons) => lessons.map((l) => (l.id === result.lesson.id ? result.lesson : l)));
      }
    },
    onSettled: () => invalidateVersionDerived(queryClient, versionId),
  });
}

/**
 * Перевірка переміщення без збереження (`?dryRun=true`) для підсвітки цілей перетягування.
 * 409 — це відповідь «не можна», а не збій: повертаємо конфлікти. Інші помилки кидаємо далі.
 */
export async function previewMove(
  versionId: Uuid,
  lessonId: Uuid,
  target: MoveLessonRequest,
  signal?: AbortSignal,
): Promise<MovePreview> {
  try {
    const result = await api.post<LessonMutationResult | undefined>(`${lessonPath(versionId, lessonId)}/move`, target, {
      query: { dryRun: true },
      signal,
    });
    return { status: 'ok', conflicts: result?.conflicts ?? [] };
  } catch (error) {
    if (isApiError(error) && error.isConflict) {
      return { status: 'blocked', conflicts: error.conflicts, message: error.message };
    }
    throw error;
  }
}

export function useCreateLesson(versionId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: TemplateLessonRequest) => api.post<LessonMutationResult>(`${versionPath(versionId)}/lessons`, body),
    onSuccess: (result) => {
      patchLessons(queryClient, versionId, (lessons) => [...lessons, result.lesson]);
    },
    onSettled: () => invalidateVersionDerived(queryClient, versionId),
  });
}

export function useUpdateLesson(versionId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lessonId, body }: { lessonId: Uuid; body: TemplateLessonRequest }) =>
      api.put<LessonMutationResult>(lessonPath(versionId, lessonId), body),
    onSuccess: (result) => {
      patchLessons(queryClient, versionId, (lessons) => lessons.map((l) => (l.id === result.lesson.id ? result.lesson : l)));
    },
    onSettled: () => invalidateVersionDerived(queryClient, versionId),
  });
}

export function useDeleteLesson(versionId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (lessonId: Uuid) => api.delete(lessonPath(versionId, lessonId)),
    onMutate: async (lessonId) => {
      await queryClient.cancelQueries({ queryKey: qk.admin.timetable.lessons(versionId, {}) });
      return { previous: patchLessons(queryClient, versionId, (lessons) => lessons.filter((l) => l.id !== lessonId)) };
    },
    onError: (_error, _lessonId, context) => restoreLessons(queryClient, versionId, context?.previous),
    onSettled: () => invalidateVersionDerived(queryClient, versionId),
  });
}

// ───────────────────────── Версії ─────────────────────────

export function useCreateVersion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateVersionRequest) => api.post<TimetableVersionDto>(versionsPath, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.admin.timetable.versions() }),
  });
}

export function usePublishVersion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ versionId, effectiveFrom }: { versionId: Uuid; effectiveFrom: string }) =>
      api.post<PublishVersionResponse>(`${versionPath(versionId)}/publish`, { effectiveFrom }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.timetable.all });
      // опублікований шаблон матеріалізовано в заняття на дати — публічний розклад теж змінився
      void queryClient.invalidateQueries({ queryKey: qk.public.scheduleAll });
    },
  });
}
