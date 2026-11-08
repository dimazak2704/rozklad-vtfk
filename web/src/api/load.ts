/**
 * Навантаження (CONTRACTS §2 «Load»): години на тиждень за групою × дисципліною × семестром, викладачі
 * за підгрупами, тижневий шаблон «цілі пари + ½», виведення з опублікованої версії розкладу.
 *
 * Поля понад LoadItemDto з types.ts — як в API core (load/internal/LoadDtos): `streamId`, `note`, `version`;
 * список приходить масивом (без сторінок), `semesterId` обовʼязковий.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  ControlForm,
  DisciplineRef,
  GroupRef,
  LessonKind,
  LoadItemDto,
  RoomType,
  TimetableVersionDto,
  Uuid,
} from './types';

export interface LoadItem extends LoadItemDto {
  streamId: Uuid | null;
  note: string | null;
  version: number;
}

export interface LoadTeacherRequest {
  /** 0 — уся група; 1..n — підгрупа, коли групу ділять. */
  subgroup: number;
  teacherId: Uuid;
}

export interface LoadItemRequest {
  semesterId: Uuid;
  groupId: Uuid;
  disciplineId: Uuid;
  lessonKind?: LessonKind;
  /** Академічні години на тиждень з одним знаком після коми: 3 → «1 + ½». */
  hoursPerWeek: number;
  /** null — сервер порахує: години на тиждень × навчальні тижні семестру. */
  plannedHours?: number | null;
  splitSubgroups?: boolean;
  teachers?: LoadTeacherRequest[];
  requiredRoomType?: RoomType | null;
  preferredRoomId?: Uuid | null;
  controlForm?: ControlForm;
  streamId?: Uuid | null;
  note?: string | null;
  version?: number;
}

/** Група × дисципліна, яку не перезаписано: навантаження вже внесли вручну чи з навчального плану. */
export interface SkippedDerivation {
  group: GroupRef;
  discipline: DisciplineRef;
  reason: string;
}

/** POST /api/admin/load/derive-from-version/{versionId} */
export interface DerivedLoadResult {
  versionId: Uuid;
  semesterId: Uuid;
  created: number;
  updated: number;
  removed: number;
  skipped: SkippedDerivation[];
  items: LoadItem[];
}

function invalidateLoad(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: qk.admin.load() });
  // план годин — основа обліку «план / факт / прогноз»
  void queryClient.invalidateQueries({ queryKey: qk.admin.accounting.all });
}

/** Навантаження семестру; з `groupId` — лише однієї групи. */
export function useLoadItems(semesterId: Uuid | null | undefined, groupId?: Uuid | null) {
  return useQuery({
    queryKey: qk.admin.load({ semesterId: semesterId ?? undefined, groupId: groupId ?? undefined }),
    queryFn: ({ signal }) =>
      api.get<LoadItem[]>('/admin/load', { semesterId: semesterId ?? undefined, groupId: groupId ?? undefined }, { signal }),
    enabled: Boolean(semesterId),
  });
}

export function useSaveLoadItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: Uuid | null; body: LoadItemRequest }) =>
      id ? api.put<LoadItem>(`/admin/load/${id}`, body) : api.post<LoadItem>('/admin/load', body),
    onSuccess: () => invalidateLoad(queryClient),
  });
}

export function useDeleteLoadItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/load/${id}`),
    onSuccess: () => invalidateLoad(queryClient),
  });
}

/** «Вивести з розкладу»: пари щотижня → 1 пара, лише чисельник чи знаменник → ½ (DOMAIN §12). */
export function useDeriveLoad() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (versionId: Uuid) => api.post<DerivedLoadResult>(`/admin/load/derive-from-version/${versionId}`),
    onSuccess: () => invalidateLoad(queryClient),
  });
}

/**
 * Версії розкладу семестру — джерело для «Вивести з розкладу». Той самий ключ і ендпоінт, що й у
 * редакторі розкладу, тож кеш спільний.
 */
export function useTimetableVersions(semesterId: Uuid | null | undefined) {
  return useQuery({
    queryKey: qk.admin.timetable.versions(semesterId ?? undefined),
    queryFn: ({ signal }) =>
      api.get<TimetableVersionDto[]>('/admin/timetable/versions', { semesterId: semesterId ?? undefined }, { signal }),
    enabled: Boolean(semesterId),
  });
}
