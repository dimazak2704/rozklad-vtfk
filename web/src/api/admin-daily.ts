/**
 * Щоденна робота диспетчера: панель, відсутності, зачеплені пари, варіанти, зміни з підтвердженням,
 * розпорядження та їхні файли (CONTRACTS §2, §6). Хуки TanStack Query; ключі — з `qk`, запити — через `api`.
 */
import {
  keepPreviousData,
  queryOptions,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import { api } from './client';
import { isApiError } from './errors';
import { useServerEvent } from './live';
import { qk } from './queryKeys';
import type {
  AbsenceDto,
  AbsenceRequest,
  AffectedLessonDto,
  ChangeDto,
  CreateChangeRequest,
  DashboardDto,
  DocumentFormat,
  IsoDate,
  LessonOptionsDto,
  OptionDto,
  OrderDto,
  OrderStatus,
  OrderSummaryDto,
  Page,
  UpdateChangeRequest,
  Uuid,
} from './types';

// ───────────────────────── Типи області (доповнення до types.ts) ─────────────────────────

/** Зачеплена пара з датою (відсутність може тривати кілька днів, а LessonView дати не має). */
export type AffectedLesson = AffectedLessonDto;

/** Варіант рішення (адитивні `date`/`pair` має лише відпрацювання ADDITIONAL). */
export type ChangeOption = OptionDto;
export type LessonOptions = LessonOptionsDto;

export interface OrdersQuery {
  from?: IsoDate;
  to?: IsoDate;
  q?: string;
  status?: OrderStatus;
  page?: number;
  size?: number;
  sort?: string;
}


// ───────────────────────── Запити ─────────────────────────

export function useDashboard(date: IsoDate) {
  return useQuery({
    queryKey: qk.admin.dashboard(date),
    queryFn: ({ signal }) => api.get<DashboardDto>('/admin/dashboard', { date }, { signal }),
    placeholderData: keepPreviousData,
    // строки підтверджень спливають і без подій: оновлюємо щохвилини
    refetchInterval: 60_000,
  });
}

/** Відсутності, що перетинають проміжок дат (лише активні). */
export function useAbsences(from: IsoDate, to: IsoDate) {
  return useQuery({
    queryKey: qk.admin.absences.list({ from, to }),
    queryFn: ({ signal }) => api.get<AbsenceDto[]>('/admin/absences', { from, to }, { signal }),
    placeholderData: keepPreviousData,
  });
}

/** Опції запиту зачеплених пар відсутності (для useQuery/useQueries). */
export function affectedLessonsQuery(absence: Pick<AbsenceDto, 'id' | 'dateFrom' | 'dateTo'>) {
  return queryOptions({
    queryKey: qk.admin.absences.affected(absence.id),
    queryFn: ({ signal }) => api.get<AffectedLesson[]>(`/admin/absences/${absence.id}/affected`, undefined, { signal }),
  });
}

/** Опції запиту змін дати (будь-якого стану). */
export function changesQuery(date: IsoDate) {
  return queryOptions({
    queryKey: qk.admin.changes.list({ date }),
    queryFn: ({ signal }) => api.get<ChangeDto[]>('/admin/changes', { date }, { signal }),
  });
}

interface Combined<T> {
  data: Array<T | undefined>;
  pending: boolean;
  error: unknown;
}

/** Стабільна функція combine: результат структурно спільний, тож посилання не змінюються без потреби. */
function combineResults<T>(results: ReadonlyArray<{ data: T | undefined; isPending: boolean; error: unknown }>): Combined<T> {
  return {
    data: results.map((r) => r.data),
    pending: results.some((r) => r.isPending),
    error: results.find((r) => r.error)?.error ?? null,
  };
}

/** Зачеплені пари кожної відсутності (окремий запит на відсутність, як у контракті). */
export function useAffectedLessons(absences: ReadonlyArray<Pick<AbsenceDto, 'id' | 'dateFrom' | 'dateTo'>>): Combined<AffectedLesson[]> {
  return useQueries({ queries: absences.map((absence) => affectedLessonsQuery(absence)), combine: combineResults<AffectedLesson[]> });
}

/** Зміни кількох дат (тиждень на екрані «Заміни»). */
export function useChangesForDates(dates: readonly IsoDate[]): Combined<ChangeDto[]> {
  return useQueries({ queries: dates.map((date) => changesQuery(date)), combine: combineResults<ChangeDto[]> });
}

export function useLessonOptions(lessonId: Uuid | null) {
  return useQuery({
    queryKey: qk.admin.lessonOptions(lessonId ?? 'none'),
    queryFn: ({ signal }) => api.get<LessonOptions>(`/admin/lessons/${lessonId ?? ''}/options`, undefined, { signal }),
    enabled: lessonId !== null,
  });
}

/** Усі зміни дати (будь-якого стану): рішення, історія, строки підтвердження. */
export function useChanges(date: IsoDate) {
  return useQuery({ ...changesQuery(date), placeholderData: keepPreviousData });
}

/** Чернетка розпорядження на дату (сервер будує або оновлює її під час запиту). */
export function useOrderDraft(date: IsoDate, enabled = true) {
  return useQuery({
    queryKey: qk.admin.orders.draft(date),
    queryFn: ({ signal }) => api.get<OrderDto>('/admin/orders/draft', { date }, { signal }),
    enabled,
  });
}

export function useOrder(id: Uuid | null) {
  return useQuery({
    queryKey: qk.admin.orders.detail(id ?? 'none'),
    queryFn: ({ signal }) => api.get<OrderDto>(`/admin/orders/${id ?? ''}`, undefined, { signal }),
    enabled: id !== null,
  });
}

export function useOrders(query: OrdersQuery, enabled = true) {
  return useQuery({
    queryKey: qk.admin.orders.list({ ...query }),
    queryFn: ({ signal }) => api.get<Page<OrderSummaryDto>>('/admin/orders', { ...query }, { signal }),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/**
 * Номер, який отримає розпорядження: останній опублікований у межах семестру + 1 (DOMAIN §8).
 * Остаточний номер присвоює сервер під час публікації — це лише попередження в діалозі.
 */
export function useNextOrderNumber(semester: { startsOn: IsoDate; endsOn: IsoDate } | undefined) {
  const query = useOrders(
    { from: semester?.startsOn, to: semester?.endsOn, status: 'PUBLISHED', sort: 'number,desc', size: 1 },
    semester !== undefined,
  );
  const last = query.data?.items[0]?.number ?? null;
  return { ...query, lastNumber: last, nextNumber: query.data ? (last ?? 0) + 1 : null };
}

// ───────────────────────── Зміни стану ─────────────────────────

/** Після будь-якої дії диспетчера оновлюємо все, що з неї випливає: пари, варіанти, панель, чернетку. */
export function invalidateDaily(queryClient: QueryClient): void {
  for (const queryKey of [qk.admin.changes.all, qk.admin.absences.all, ['admin', 'dashboard'], ['admin', 'lesson-options'], qk.admin.orders.all]) {
    void queryClient.invalidateQueries({ queryKey });
  }
}

export function useCreateAbsence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: AbsenceRequest) => api.post<AbsenceDto>('/admin/absences', body),
    onSuccess: () => invalidateDaily(queryClient),
  });
}

/** PUT відсутності з `version` (оптимістичне блокування): на 409 `stale-data` одразу перечитуємо дані. */
export function useUpdateAbsence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: AbsenceRequest }) => api.put<AbsenceDto>(`/admin/absences/${id}`, body),
    onSuccess: () => invalidateDaily(queryClient),
    onError: (error) => {
      if (isApiError(error) && error.isStaleData) invalidateDaily(queryClient);
    },
  });
}

export function useCancelAbsence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/absences/${id}`),
    onSuccess: () => invalidateDaily(queryClient),
  });
}

export function useCreateChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateChangeRequest) => api.post<ChangeDto>('/admin/changes', body),
    onSuccess: () => invalidateDaily(queryClient),
  });
}

/**
 * Змінити причину чи аудиторію рішення (PUT з `version`). Інший викладач чи дисципліна — лише через
 * скасування й нове рішення. На 409 `stale-data` (рішення вже змінив бот чи інший диспетчер) перечитуємо зміни.
 */
export function useUpdateChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateChangeRequest }) => api.put<ChangeDto>(`/admin/changes/${id}`, body),
    onSuccess: () => invalidateDaily(queryClient),
    onError: (error) => {
      if (isApiError(error) && error.isStaleData) invalidateDaily(queryClient);
    },
  });
}

/** Скасувати рішення (DELETE → CANCELLED): пара знову потребує рішення. */
export function useCancelChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/changes/${id}`),
    onSuccess: () => invalidateDaily(queryClient),
  });
}

export function useResendConfirmation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.post<ChangeDto>(`/admin/changes/${id}/request-confirmation`),
    onSuccess: () => invalidateDaily(queryClient),
  });
}

/** Підтвердити за викладача (диспетчер домовився телефоном). */
export function useConfirmChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.post<ChangeDto>(`/admin/changes/${id}/confirm`),
    onSuccess: () => invalidateDaily(queryClient),
  });
}

export function useUpdateOrderNotes() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, notes }: { id: Uuid; notes: string[] }) => api.put<OrderDto>(`/admin/orders/${id}/notes`, { notes }),
    onSuccess: (order) => {
      queryClient.setQueryData(qk.admin.orders.draft(order.date), order);
      void queryClient.invalidateQueries({ queryKey: qk.admin.orders.all });
    },
  });
}

export function usePublishOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.post<OrderDto>(`/admin/orders/${id}/publish`),
    onSuccess: (order) => {
      queryClient.setQueryData(qk.admin.orders.detail(order.id), order);
      invalidateDaily(queryClient);
    },
  });
}

/** Живі оновлення сторінок щоденної роботи: варіанти й чернетка залежать від кожної зміни. */
export function useDailyLiveSync(): void {
  const queryClient = useQueryClient();
  useServerEvent('change.updated', () => {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'lesson-options'] });
    void queryClient.invalidateQueries({ queryKey: qk.admin.orders.all });
  });
  useServerEvent('absence.created', () => {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'lesson-options'] });
  });
  useServerEvent('absence.updated', () => {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'lesson-options'] });
  });
}

// ───────────────────────── Файли розпорядження ─────────────────────────

export const DOCUMENT_LABEL: Record<DocumentFormat, string> = { docx: 'DOCX', pdf: 'PDF', png: 'PNG' };

/** Імʼя файлу для збереження: «Розпорядження №36 від 09.11.2026.pdf». */
export function orderFileName(order: Pick<OrderDto, 'number' | 'date' | 'kind'>, format: DocumentFormat): string {
  const [year, month, day] = order.date.split('-');
  const kind = order.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження';
  return `${kind} №${order.number ?? 'без номера'} від ${day}.${month}.${year}.${format}`;
}

export function fetchOrderFile(orderId: Uuid, format: DocumentFormat): Promise<Blob> {
  return api.blob(`/documents/orders/${orderId}.${format}`, undefined, { timeoutMs: 60_000 });
}

/** Завантажує файл опублікованого розпорядження (браузер зберігає його під зрозумілою назвою). */
export async function downloadOrderFile(order: Pick<OrderDto, 'id' | 'number' | 'date' | 'kind'>, format: DocumentFormat): Promise<void> {
  const blob = await fetchOrderFile(order.id, format);
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement('a');
    link.href = url;
    link.download = orderFileName(order, format);
    document.body.append(link);
    link.click();
    link.remove();
  } finally {
    // посилання вже клікнули: памʼять можна звільнити трохи згодом
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }
}

/** Чи вміє браузер класти зображення в буфер обміну. */
export function canCopyImages(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.clipboard?.write === 'function' && typeof ClipboardItem !== 'undefined';
}

/**
 * PNG розпорядження в буфер обміну (для Instagram і чатів). Обіцянка передається в ClipboardItem одразу,
 * щоб Safari не вважав запис поза жестом користувача.
 */
export async function copyOrderPng(orderId: Uuid): Promise<void> {
  if (!canCopyImages()) throw new Error('Цей браузер не вміє копіювати зображення. Завантажте PNG і вставте файл.');
  const png = fetchOrderFile(orderId, 'png').then((blob) => (blob.type === 'image/png' ? blob : new Blob([blob], { type: 'image/png' })));
  await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]);
}
