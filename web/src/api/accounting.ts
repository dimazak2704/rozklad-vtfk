/**
 * Облік годин (CONTRACTS §2 «Accounting», DOMAIN §9): план / факт / прогноз за групою × дисципліною,
 * дата, коли години буде вичитано, пропозиції дій і місячна статистика замін викладачів.
 * Поле `suggestion.lessonIds` — як в API core (accounting/internal/web/AccountingRowDto).
 */
import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type { AccountingRowDto, AccountingSuggestion, TeacherStatsDto, Uuid } from './types';

export interface AccountingSuggestionDetail extends AccountingSuggestion {
  /** Скільки пар зняти (DISCIPLINE_FINISHED) або додати (ADDITIONAL). */
  lessons: number;
  /** Заняття після дати «вичитано», які варто зняти (лише DISCIPLINE_FINISHED). */
  lessonIds?: Uuid[] | null;
}

export interface AccountingRow extends Omit<AccountingRowDto, 'suggestion'> {
  suggestion?: AccountingSuggestionDetail | null;
}

/** План/факт/прогноз; без `semesterId` — активний семестр, без `groupId` — усі групи. */
export function useAccountingRows(semesterId: Uuid | null | undefined, groupId?: Uuid | null) {
  return useQuery({
    queryKey: qk.admin.accounting.rows({ semesterId: semesterId ?? undefined, groupId: groupId ?? undefined }),
    queryFn: ({ signal }) =>
      api.get<AccountingRow[]>('/admin/accounting', { semesterId: semesterId ?? undefined, groupId: groupId ?? undefined }, { signal }),
    enabled: Boolean(semesterId),
  });
}

/** Заміни й пропущені пари викладачів за місяць `2026-10`. */
export function useTeacherStats(month: string) {
  return useQuery({
    queryKey: qk.admin.accounting.teachers(month),
    queryFn: ({ signal }) => api.get<TeacherStatsDto[]>('/admin/accounting/teachers', { month }, { signal }),
  });
}
