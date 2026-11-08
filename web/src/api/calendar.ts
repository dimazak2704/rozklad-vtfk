/**
 * Календар (CONTRACTS §2 «Calendar»): семестри й активація, тижні з чисельником/знаменником і ручними
 * перевизначеннями, профілі дзвінків, календарні періоди (канікули, практика, режими навчання).
 *
 * Поля понад @inferred-типи з types.ts — як в API core (calendar/internal/CalendarDtos): `teachingWeeks`,
 * `bellProfile`, `computedParity`, `defaultProfile`, `groups`, `version`.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  BellProfileDto,
  BellProfileSlotDto,
  CalendarPeriodDto,
  CalendarPeriodKind,
  GroupRef,
  IsoDate,
  PeriodScope,
  Ref,
  SemesterDto,
  SemesterWeekDto,
  ShiftWindowDto,
  StudyMode,
  Uuid,
  WeekParity,
} from './types';

// ───────────────────────── Типи ─────────────────────────

export interface CalendarSemester extends SemesterDto {
  /** «2026/2027». */
  academicYear: string | null;
  /** Скільки навчальних тижнів: `weeksCount` або тижні між датами. */
  teachingWeeks: number;
  bellProfile: Ref | null;
  version: number;
}

export interface SemesterRequest {
  academicYear?: string | null;
  number: number;
  /** «I семестр 2026/2027», якщо порожньо. */
  name?: string | null;
  startsOn: IsoDate;
  endsOn: IsoDate;
  firstWeekParity?: WeekParity;
  weeksCount?: number | null;
  /** null — типовий профіль із налаштувань. */
  bellProfileId?: Uuid | null;
  version?: number;
}

/** Тиждень семестру: `computedParity` — за чергуванням, `parity` — що діє (ручне перевизначення важливіше). */
export interface CalendarWeek extends SemesterWeekDto {
  weekEnd: IsoDate;
  computedParity: WeekParity;
}

export interface CalendarBellProfile extends BellProfileDto {
  /** Профіль за замовчуванням із налаштувань (`activeBellProfileId`). */
  defaultProfile: boolean;
  version: number;
}

export interface BellProfileRequest {
  name: string;
  active?: boolean;
  slots: BellProfileSlotDto[];
  version?: number;
}

export interface CalendarPeriod extends CalendarPeriodDto {
  groups: GroupRef[];
  version: number;
}

export interface CalendarPeriodRequest {
  semesterId: Uuid;
  kind: CalendarPeriodKind;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  /** Обовʼязковий лише для STUDY_MODE. */
  studyMode?: StudyMode | null;
  /** Текст примітки в розпорядженні на дати періоду. */
  orderNote?: string | null;
  scope?: PeriodScope;
  courses?: number[] | null;
  groupIds?: Uuid[] | null;
  version?: number;
}

/**
 * Календар впливає на все: на дати занять у публічному розкладі, дзвінки, облік годин і чернетки
 * розпоряджень (примітки періодів), тож після змін оновлюємо ці області.
 */
function invalidateCalendar(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: qk.admin.calendar.all });
  void queryClient.invalidateQueries({ queryKey: qk.admin.accounting.all });
  void queryClient.invalidateQueries({ queryKey: qk.admin.orders.all });
  void queryClient.invalidateQueries({ queryKey: qk.public.all });
}

// ───────────────────────── Семестри ─────────────────────────

export function useSemesters() {
  return useQuery({
    queryKey: qk.admin.calendar.semesters(),
    queryFn: ({ signal }) => api.get<CalendarSemester[]>('/admin/semesters', undefined, { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useSaveSemester() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: Uuid | null; body: SemesterRequest }) =>
      id ? api.put<CalendarSemester>(`/admin/semesters/${id}`, body) : api.post<CalendarSemester>('/admin/semesters', body),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

/** Активний семестр — той, що бачить публічний сайт і бере за замовчуванням адмінка; він лише один. */
export function useActivateSemester() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.post<CalendarSemester>(`/admin/semesters/${id}/activate`),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

export function useDeleteSemester() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/semesters/${id}`),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

// ───────────────────────── Тижні ─────────────────────────

export function useSemesterWeeks(semesterId: Uuid | null | undefined) {
  return useQuery({
    queryKey: qk.admin.calendar.weeks(semesterId ?? ''),
    queryFn: ({ signal }) => api.get<CalendarWeek[]>(`/admin/semesters/${semesterId ?? ''}/weeks`, undefined, { signal }),
    enabled: Boolean(semesterId),
  });
}

export interface WeekOverrideInput {
  weekStart: IsoDate;
  /** null — прибрати перевизначення, тиждень знову йде за чергуванням. */
  parity: WeekParity | null;
}

/** Ставить чи знімає ручну парність одного тижня; інші тижні не змінюються. Оптимістично, з відкатом. */
export function useOverrideWeek(semesterId: Uuid) {
  const queryClient = useQueryClient();
  const key = qk.admin.calendar.weeks(semesterId);
  return useMutation<void, Error, WeekOverrideInput, { previous: CalendarWeek[] | undefined }>({
    mutationFn: async ({ weekStart, parity }) => {
      if (parity) await api.put<CalendarWeek>(`/admin/semesters/${semesterId}/weeks/${weekStart}`, { parity });
      else await api.delete(`/admin/semesters/${semesterId}/weeks/${weekStart}`);
    },
    onMutate: async ({ weekStart, parity }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<CalendarWeek[]>(key);
      queryClient.setQueryData<CalendarWeek[]>(key, (weeks) =>
        weeks?.map((week) =>
          week.weekStart === weekStart
            ? { ...week, parity: parity ?? week.computedParity, overridden: parity !== null && parity !== week.computedParity }
            : week,
        ),
      );
      return { previous };
    },
    onError: (_error, _input, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
    },
    onSettled: () => invalidateCalendar(queryClient),
  });
}

// ───────────────────────── Профілі дзвінків ─────────────────────────

export function useBellProfiles() {
  return useQuery({
    queryKey: qk.admin.calendar.bellProfiles(),
    queryFn: ({ signal }) => api.get<CalendarBellProfile[]>('/admin/bell-profiles', undefined, { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useSaveBellProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: Uuid | null; body: BellProfileRequest }) =>
      id ? api.put<CalendarBellProfile>(`/admin/bell-profiles/${id}`, body) : api.post<CalendarBellProfile>('/admin/bell-profiles', body),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

export function useDeleteBellProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/bell-profiles/${id}`),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

// ───────────────────────── Календарні періоди ─────────────────────────

export function useCalendarPeriods(semesterId: Uuid | null | undefined) {
  return useQuery({
    queryKey: qk.admin.calendar.periods(semesterId ?? undefined),
    queryFn: ({ signal }) =>
      api.get<CalendarPeriod[]>('/admin/calendar-periods', { semesterId: semesterId ?? undefined }, { signal }),
    enabled: Boolean(semesterId),
  });
}

export function useSaveCalendarPeriod() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: Uuid | null; body: CalendarPeriodRequest }) =>
      id ? api.put<CalendarPeriod>(`/admin/calendar-periods/${id}`, body) : api.post<CalendarPeriod>('/admin/calendar-periods', body),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

export function useDeleteCalendarPeriod() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/calendar-periods/${id}`),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

/** Вікна змін: у яких парах навчається перша й друга зміна (DOMAIN §2). Лише читання. */
export function useShiftWindows() {
  return useQuery({
    queryKey: qk.admin.calendar.shiftWindows(),
    queryFn: ({ signal }) => api.get<ShiftWindowDto[]>('/admin/shift-windows', undefined, { signal }),
    staleTime: 10 * 60_000,
  });
}

/** Активний семестр зі списку (або перший, якщо активного ще немає). */
export function activeSemesterOf(semesters: readonly CalendarSemester[] | undefined): CalendarSemester | undefined {
  return semesters?.find((s) => s.active) ?? semesters?.[0];
}
