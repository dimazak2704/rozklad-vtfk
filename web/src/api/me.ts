/**
 * Кабінет викладача (CONTRACTS §3): профіль, мій розклад, запити на заміну, відсутності, побажання
 * і код привʼязки Telegram (CONTRACTS §6: викладач може створити код лише для себе).
 * Живі оновлення: макет кабінету тримає SSE і на `change.updated` інвалідує `qk.me.all` (src/api/live.ts).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notify } from '@/components/ui';
import { formatDateRange } from '@/lib/date';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  AbsenceDto,
  ChangeDto,
  ChangeStatus,
  IsoDate,
  MeDto,
  MyAbsenceRequest,
  PreferenceDto,
  ReplyDecision,
  ScheduleDto,
  Uuid,
} from './types';

/** GET /api/me — хто увійшов і з яким викладачем повʼязаний обліковий запис. */
export function useMe() {
  return useQuery({
    queryKey: qk.me.profile(),
    queryFn: ({ signal }) => api.get<MeDto>('/me', undefined, { signal }),
    staleTime: 5 * 60_000,
  });
}

/** GET /api/me/schedule?from&to — мої пари (і ті, де мене замінили, і ті, де замінюю я). */
export function useMySchedule(from: IsoDate, to: IsoDate, { enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: qk.me.schedule(from, to),
    queryFn: ({ signal }) => api.get<ScheduleDto>('/me/schedule', { from, to }, { signal }),
    enabled,
    placeholderData: (previous) => previous,
  });
}

// ───────────────────────── Запити на заміну ─────────────────────────

/** GET /api/me/changes — заміни, де я новий викладач (усі статуси; сторінка ділить їх сама). */
export function useMyChanges() {
  return useQuery({
    queryKey: qk.me.changes(),
    queryFn: ({ signal }) => api.get<ChangeDto[]>('/me/changes', undefined, { signal }),
  });
}

export interface ReplyVariables {
  id: Uuid;
  decision: ReplyDecision;
  comment?: string;
}

const REPLY_STATUS: Record<ReplyDecision, ChangeStatus> = { CONFIRM: 'CONFIRMED', DECLINE: 'DECLINED' };

/**
 * Стан заміни після відповіді — як у core. Прохання (CONFIRM) переходить у CONFIRMED чи DECLINED. Повідомлення
 * (NOTIFY): «Добре» лишає статус, «Не можу» відхиляє ще не опубліковану заміну, а опубліковану лишає (заперечення).
 */
export function answered(change: ChangeDto, decision: ReplyDecision, at: string): ChangeDto {
  if (change.approvalMode !== 'NOTIFY') return { ...change, status: REPLY_STATUS[decision], respondedAt: at, teacherAnswer: decision };
  const status = decision === 'DECLINE' && change.status === 'CONFIRMED' ? 'DECLINED' : change.status;
  return { ...change, status, respondedAt: at, teacherAnswer: decision };
}

/**
 * POST /api/me/changes/{id}/confirm|decline. Оптимістично: картка одразу переходить у новий стан,
 * а якщо сервер відмовив — повертається назад і зʼявляється пояснення.
 */
export function useReplyToChange() {
  const queryClient = useQueryClient();
  const key = qk.me.changes();
  return useMutation({
    mutationFn: ({ id, decision, comment }: ReplyVariables) =>
      api.post<ChangeDto>(`/me/changes/${encodeURIComponent(id)}/${decision === 'CONFIRM' ? 'confirm' : 'decline'}`, {
        comment: comment?.trim() ? comment.trim() : null,
      }),
    onMutate: async ({ id, decision }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<ChangeDto[]>(key);
      queryClient.setQueryData<ChangeDto[]>(key, (list) =>
        list?.map((change) => (change.id === id ? answered(change, decision, new Date().toISOString()) : change)),
      );
      return { previous };
    },
    onError: (error, variables, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      notify.error(error, variables.decision === 'CONFIRM' ? 'Не вдалося підтвердити заміну' : 'Не вдалося відмовитися від заміни');
    },
    onSuccess: (updated) => {
      queryClient.setQueryData<ChangeDto[]>(key, (list) => list?.map((change) => (change.id === updated.id ? updated : change)));
    },
    // розклад теж змінився (підтверджена заміна зʼявляється в моїх парах)
    onSettled: () => queryClient.invalidateQueries({ queryKey: qk.me.all }),
  });
}

// ───────────────────────── Відсутності ─────────────────────────

/** GET /api/me/absences — мої відсутності (будь-яке джерело: сайт, бот, диспетчер). */
export function useMyAbsences() {
  return useQuery({
    queryKey: qk.me.absences(),
    queryFn: ({ signal }) => api.get<AbsenceDto[]>('/me/absences', undefined, { signal }),
    // core віддає й скасовані (status CANCELLED): у кабінеті показуємо лише чинні
    select: (list) => list.filter((absence) => absence.status === 'ACTIVE'),
  });
}

/** POST /api/me/absences — викладача сервер бере з токена. Помилки полів показує форма. */
export function useCreateAbsence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MyAbsenceRequest) => api.post<AbsenceDto>('/me/absences', body),
    onSuccess: (created) => {
      queryClient.setQueryData<AbsenceDto[]>(qk.me.absences(), (list) => (list ? [created, ...list] : list));
      void queryClient.invalidateQueries({ queryKey: qk.me.absences() });
    },
  });
}

/**
 * DELETE /api/me/absences/{id} — лише власні й лише ті, що ще не почалися (422 `ABSENCE_STARTED`); core ставить CANCELLED.
 * Сповіщення про успіх — тут, а не в `mutate(…, {onSuccess})` рядка: оптимістичне оновлення прибирає рядок
 * одразу, і колбеки виклику, привʼязані до його спостерігача, уже не спрацьовують.
 */
export function useDeleteAbsence() {
  const queryClient = useQueryClient();
  const key = qk.me.absences();
  return useMutation({
    mutationFn: (absence: Pick<AbsenceDto, 'id' | 'dateFrom' | 'dateTo'>) => api.delete(`/me/absences/${encodeURIComponent(absence.id)}`),
    onMutate: async ({ id }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<AbsenceDto[]>(key);
      queryClient.setQueryData<AbsenceDto[]>(key, (list) => list?.filter((absence) => absence.id !== id));
      return { previous };
    },
    onError: (error, _id, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      notify.error(error, 'Не вдалося видалити відсутність');
    },
    onSuccess: (_result, absence) => notify.success('Відсутність видалено', { description: formatDateRange(absence.dateFrom, absence.dateTo) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
  });
}

// ───────────────────────── Побажання ─────────────────────────

/** GET /api/me/preferences?semesterId — небажані й неможливі пари на семестр. */
export function useMyPreferences(semesterId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.me.preferences(semesterId),
    queryFn: ({ signal }) => api.get<PreferenceDto[]>('/me/preferences', { semesterId }, { signal }),
    enabled: Boolean(semesterId),
  });
}

/** PUT /api/me/preferences?semesterId — повний список замість попереднього; відповідь — збережений список. */
export function useSavePreferences(semesterId: Uuid | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (preferences: PreferenceDto[]) => api.put<PreferenceDto[]>('/me/preferences', preferences, { query: { semesterId } }),
    onSuccess: (saved) => queryClient.setQueryData(qk.me.preferences(semesterId), saved),
  });
}
