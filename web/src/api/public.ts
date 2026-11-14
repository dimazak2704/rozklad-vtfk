/**
 * Публічні дані без входу (CONTRACTS §1): довідники, розклади, заміни, профілі дзвінків, ICS, запрошення.
 * Запити оболонки (meta, search, дзвінки на дату) — у meta.ts.
 *
 * Розклад і довідники мають `networkMode: 'offlineFirst'`: без мережі запит усе одно йде, і Service Worker
 * (PWA) віддає збережену відповідь — так останній переглянутий розклад відкривається офлайн.
 *
 * Розклад і заміни персонал (ADMIN, TEACHER) просить з токеном — сервер віддає йому весь архів; гість — без
 * токена, лише з `publicHistoryFrom` (meta). Хто питає, записано і в ключ запиту (`usePublicViewer`), тож під
 * ключем персоналу лежить лише відповідь персоналу: без дійсного токена запит не йде гостьовим (`viewerAuth`).
 */
import { useMutation, useQuery, type QueryKey } from '@tanstack/react-query';
import { usePublicViewer, type PublicViewer } from '@/auth/usePublicViewer';
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

/** `/public/schedule/{group|teacher|room}/{id}` — шлях розкладу відносно API. */
export function publicSchedulePath(owner: EntityType, id: Uuid): string {
  return `/public/schedule/${OWNER_SEGMENT[owner]}/${encodeURIComponent(id)}`;
}

/**
 * Токен для запиту, відповідь якого залежить від входу. Персоналу він обовʼязковий: якщо строк минув і оновити не
 * вдалося, запит не йде гостьовим (відповідь гостю лягла б під ключ персоналу), а завершується 401 — токен
 * забувається, і сторінка перепитує як гість. Гість питає без токена.
 */
function viewerAuth(viewer: PublicViewer | null): 'required' | false {
  return viewer === 'staff' ? 'required' : false;
}

/** Ключ розкладу: [..., owner, id, {from, to}, viewer] — id на позиції 3, хто питає — на 5. */
function sameOwnerKey(key: QueryKey | undefined, owner: EntityType, id: Uuid | undefined, viewer: PublicViewer | null): boolean {
  return Array.isArray(key) && key[2] === owner && key[3] === id && key[5] === viewer;
}

/**
 * GET /api/public/schedule/{group|teacher|room}/{id}?from=&to= (не більше 31 дня).
 * Поки вантажиться інший тиждень того самого власника, показуємо попередній (`isPlaceholderData`),
 * а не силует — сторінка не «блимає» при гортанні тижнів. Гостю давніший за `publicHistoryFrom` тиждень
 * сервер не віддає (404 `history-not-public`, `ApiError.isHistoryNotPublic`); поки вхід визначається, запит чекає.
 */
export function usePublicSchedule(
  owner: EntityType,
  id: Uuid | undefined,
  from: IsoDate,
  to: IsoDate,
  { enabled = true }: { enabled?: boolean } = {},
) {
  const viewer = usePublicViewer();
  return useQuery({
    queryKey: qk.public.schedule(owner, id ?? '', from, to, viewer ?? 'guest'),
    queryFn: ({ signal }) =>
      api.get<ScheduleDto>(publicSchedulePath(owner, id ?? ''), { from, to }, { signal, auth: viewerAuth(viewer) }),
    enabled: enabled && viewer !== null && Boolean(id && from && to),
    // дані іншого глядача (персонал → гість після виходу) заглушкою не показуємо
    placeholderData: (previous, previousQuery) => (sameOwnerKey(previousQuery?.queryKey, owner, id, viewer) ? previous : undefined),
    networkMode: 'offlineFirst',
  });
}

/**
 * GET /api/public/changes?date= — заміни на дату, згруповані за групами. Гостю дата, давніша за
 * `publicHistoryFrom`, — 404 `history-not-public`; персонал питає з токеном і бачить увесь архів.
 */
export function usePublicChanges(date: IsoDate, { refetchInterval }: { refetchInterval?: number } = {}) {
  const viewer = usePublicViewer();
  return useQuery({
    queryKey: qk.public.changes(date, viewer ?? 'guest'),
    queryFn: ({ signal }) => api.get<PublicChangesDto>('/public/changes', { date }, { signal, auth: viewerAuth(viewer) }),
    enabled: viewer !== null,
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

/**
 * Завантажує .ics через клієнт API (з тайм-аутом і розбором помилок) і віддає браузеру як файл. Персонал
 * завантажує з токеном — увесь семестр; гість — з тижня `publicHistoryFrom`. Підписка (`icsUrls`) токена не несе:
 * календарі телефонів і Google його не надсилають.
 */
export async function downloadIcs(owner: IcsOwner, id: Uuid, fileName: string, viewer: PublicViewer | null): Promise<void> {
  const blob = await api.blob(icsPath(owner, id), undefined, { auth: viewer === 'staff' });
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

/**
 * Файл опублікованого розпорядження (DOCX/PDF/PNG) — публічний для опублікованих (CONTRACTS §6). Звичайне
 * посилання не несе токена, тож давні розпорядження гостю сервер не віддає; персоналу — `openOrderDocument`.
 */
export function orderDocumentUrl(orderId: Uuid, format: DocumentFormat): string {
  return `${API_BASE}/documents/orders/${encodeURIComponent(orderId)}.${format}`;
}

/** Скільки живе адреса завантаженого файлу (blob:): вистачає, щоб вкладка відкрила його й людина зберегла. */
const DOCUMENT_URL_TTL_MS = 10 * 60_000;

/**
 * Файл розпорядження для персоналу — запитом з токеном (давні розпорядження сервер віддає лише так).
 * PDF і PNG відкриваються в новій вкладці, DOCX — завантажується. Вкладку відкриваємо одразу, у тому ж
 * натисканні (інакше браузер заблокує спливне вікно), а файл ставимо в неї, щойно він завантажився. Якщо
 * вкладку не дали відкрити, файл зберігається під назвою від сервера (як за звичайним посиланням гостя;
 * `title` — лише коли сервер назви не дав). Людина закрила вкладку «Завантажуємо…» — це скасування: файл не
 * зберігаємо. Помилку (і закриту порожню вкладку) отримує викликач.
 */
export async function openOrderDocument(orderId: Uuid, format: DocumentFormat, title: string): Promise<void> {
  const tab = format === 'docx' ? null : window.open('', '_blank');
  if (tab) {
    tab.document.title = title;
    tab.document.body.textContent = 'Завантажуємо розпорядження…';
  }
  let file: File;
  try {
    file = await api.file(`/documents/orders/${encodeURIComponent(orderId)}.${format}`, undefined, { timeoutMs: 60_000 });
  } catch (error) {
    tab?.close();
    throw error;
  }
  if (!tab) {
    saveBlob(file, file.name || title);
    return;
  }
  if (tab.closed) return;
  const url = URL.createObjectURL(file);
  // вкладка — лише для перегляду: без доступу до сторінки, що її відкрила
  tab.opener = null;
  tab.location.href = url;
  setTimeout(() => URL.revokeObjectURL(url), DOCUMENT_URL_TTL_MS);
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
