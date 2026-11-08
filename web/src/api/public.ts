/**
 * Публічні дані без входу (CONTRACTS §1): довідники, розклади, заміни, профілі дзвінків, ICS, запрошення.
 * Запити оболонки (meta, search, дзвінки на дату) — у meta.ts.
 *
 * Розклад і довідники мають `networkMode: 'offlineFirst'`: без мережі запит усе одно йде, і Service Worker
 * (PWA) віддає збережену відповідь — так останній переглянутий розклад відкривається офлайн.
 */
import { useMutation, useQuery, type QueryKey } from '@tanstack/react-query';
import { API_BASE, api } from './client';
import { qk } from './queryKeys';
import type {
  AcceptInviteRequest,
  AcceptInviteResponse,
  BellsDto,
  DocumentFormat,
  EntityType,
  InviteInfoDto,
  IsoDate,
  PublicChangesDto,
  PublicGroupDto,
  PublicRoomDto,
  PublicTeacherDto,
  ScheduleDto,
  Uuid,
} from './types';

/** Довідники змінюються кілька разів на рік — годину вважаємо їх свіжими. */
const CATALOG_STALE_MS = 60 * 60_000;

/** Сегмент шляху `/public/schedule/{group|teacher|room}/{id}`. */
export const OWNER_SEGMENT: Record<EntityType, 'group' | 'teacher' | 'room'> = {
  GROUP: 'group',
  TEACHER: 'teacher',
  ROOM: 'room',
};

export function usePublicGroups() {
  return useQuery({
    queryKey: qk.public.groups(),
    queryFn: ({ signal }) => api.get<PublicGroupDto[]>('/public/groups', undefined, { signal, auth: false }),
    staleTime: CATALOG_STALE_MS,
    networkMode: 'offlineFirst',
  });
}

export function usePublicTeachers() {
  return useQuery({
    queryKey: qk.public.teachers(),
    queryFn: ({ signal }) => api.get<PublicTeacherDto[]>('/public/teachers', undefined, { signal, auth: false }),
    staleTime: CATALOG_STALE_MS,
    networkMode: 'offlineFirst',
  });
}

export function usePublicRooms() {
  return useQuery({
    queryKey: qk.public.rooms(),
    queryFn: ({ signal }) => api.get<PublicRoomDto[]>('/public/rooms', undefined, { signal, auth: false }),
    staleTime: CATALOG_STALE_MS,
    networkMode: 'offlineFirst',
  });
}

/** Ключ розкладу: [..., owner, id, {from, to}] — id на позиції 3. */
function sameOwnerKey(key: QueryKey | undefined, owner: EntityType, id: Uuid | undefined): boolean {
  return Array.isArray(key) && key[2] === owner && key[3] === id;
}

/**
 * GET /api/public/schedule/{group|teacher|room}/{id}?from=&to= (не більше 31 дня).
 * Поки вантажиться інший тиждень того самого власника, показуємо попередній (`isPlaceholderData`),
 * а не силует — сторінка не «блимає» при гортанні тижнів.
 */
export function usePublicSchedule(owner: EntityType, id: Uuid | undefined, from: IsoDate, to: IsoDate) {
  return useQuery({
    queryKey: qk.public.schedule(owner, id ?? '', from, to),
    queryFn: ({ signal }) =>
      api.get<ScheduleDto>(
        `/public/schedule/${OWNER_SEGMENT[owner]}/${encodeURIComponent(id ?? '')}`,
        { from, to },
        { signal, auth: false },
      ),
    enabled: Boolean(id),
    placeholderData: (previous, previousQuery) => (sameOwnerKey(previousQuery?.queryKey, owner, id) ? previous : undefined),
    networkMode: 'offlineFirst',
  });
}

/** GET /api/public/changes?date= — заміни на дату, згруповані за групами. */
export function usePublicChanges(date: IsoDate, { refetchInterval }: { refetchInterval?: number } = {}) {
  return useQuery({
    queryKey: qk.public.changes(date),
    queryFn: ({ signal }) => api.get<PublicChangesDto>('/public/changes', { date }, { signal, auth: false }),
    refetchInterval,
    networkMode: 'offlineFirst',
  });
}

/** GET /api/public/bells без дати — профіль, що діє, і всі профілі зі слотами. */
export function useBellProfiles() {
  return useQuery({
    queryKey: qk.public.bells(),
    queryFn: ({ signal }) => api.get<BellsDto>('/public/bells', undefined, { signal, auth: false }),
    staleTime: CATALOG_STALE_MS,
    networkMode: 'offlineFirst',
  });
}

// ───────────────────────── Календар (ICS) ─────────────────────────

export type IcsOwner = Extract<EntityType, 'GROUP' | 'TEACHER'>;

/** `/public/ics/group/{id}.ics` — шлях відносно API. */
export function icsPath(owner: IcsOwner, id: Uuid): string {
  return `/public/ics/${OWNER_SEGMENT[owner]}/${encodeURIComponent(id)}.ics`;
}

/** Повна адреса календаря для підписки: http(s) і webcal (календарі телефонів підписуються саме так). */
export function icsUrls(owner: IcsOwner, id: Uuid, origin: string = window.location.origin): { https: string; webcal: string } {
  const base = /^https?:\/\//.test(API_BASE) ? API_BASE : `${origin}${API_BASE}`;
  const https = `${base}${icsPath(owner, id)}`;
  return { https, webcal: https.replace(/^https?:/, 'webcal:') };
}

/** Завантажує .ics через клієнт API (з тайм-аутом і розбором помилок) і віддає браузеру як файл. */
export async function downloadIcs(owner: IcsOwner, id: Uuid, fileName: string): Promise<void> {
  const blob = await api.blob(icsPath(owner, id), undefined, { auth: false });
  saveBlob(blob, fileName);
}

/** Зберегти Blob як файл (посилання з атрибутом download). */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.rel = 'noopener';
  document.body.append(link);
  link.click();
  link.remove();
  // браузер уже забрав файл — звільняємо памʼять у наступному кадрі
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/** Файл опублікованого розпорядження (DOCX/PDF/PNG) — публічний для опублікованих (CONTRACTS §6). */
export function orderDocumentUrl(orderId: Uuid, format: DocumentFormat): string {
  return `${API_BASE}/documents/orders/${encodeURIComponent(orderId)}.${format}`;
}

// ───────────────────────── Запрошення ─────────────────────────

/** GET /api/public/invites/{token} */
export function useInvite(token: string) {
  return useQuery({
    queryKey: qk.public.invite(token),
    queryFn: ({ signal }) =>
      api.get<InviteInfoDto>(`/public/invites/${encodeURIComponent(token)}`, undefined, { signal, auth: false }),
    enabled: token.length > 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
}

/** POST /api/public/invites/{token}/accept → 201 {username}; 409 — логін зайнятий; 410 — термін минув. */
export function useAcceptInvite(token: string) {
  return useMutation({
    mutationFn: (body: AcceptInviteRequest) =>
      api.post<AcceptInviteResponse>(`/public/invites/${encodeURIComponent(token)}/accept`, body, { auth: false }),
  });
}
