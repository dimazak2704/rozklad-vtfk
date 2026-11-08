import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { useAbsences, useAffectedLessons, useChangesForDates } from '@/api/admin-daily';
import { qk } from '@/api/queryKeys';
import type { AbsenceDto, ChangeDto, IsoDate, Uuid } from '@/api/types';
import { addDays, compareIsoDates, endOfStudyWeek, isWeekend, startOfWeek } from '@/lib/date';
import { groupByTeacher, toEntries, type LessonEntry, type TeacherGroup } from '../model';

export type Period = 'day' | 'week';

export function periodRange(date: IsoDate, period: Period): { from: IsoDate; to: IsoDate } {
  return period === 'day' ? { from: date, to: date } : { from: startOfWeek(date), to: endOfStudyWeek(date) };
}

function studyDates(from: IsoDate, to: IsoDate): IsoDate[] {
  const dates: IsoDate[] = [];
  for (let date = from; compareIsoDates(date, to) <= 0; date = addDays(date, 1)) {
    if (!isWeekend(date)) dates.push(date);
  }
  return dates;
}

const NO_ABSENCES: AbsenceDto[] = [];

export interface ChangesWorkspace {
  from: IsoDate;
  to: IsoDate;
  absences: AbsenceDto[];
  entries: LessonEntry[];
  groups: TeacherGroup[];
  changesById: ReadonlyMap<Uuid, ChangeDto>;
  /** Перше завантаження ще триває (силуети). */
  loading: boolean;
  error: unknown;
  retry: () => void;
}

/**
 * Дані екрана «Заміни» за період: відсутності, їхні зачеплені пари з рішеннями і зміни кожної дати
 * (для строків підтвердження й історії). Пари поза періодом відкидаються.
 */
export function useChangesWorkspace(date: IsoDate, period: Period): ChangesWorkspace {
  const queryClient = useQueryClient();
  const { from, to } = periodRange(date, period);
  const absencesQuery = useAbsences(from, to);
  const absences = absencesQuery.data ?? NO_ABSENCES;
  const absenceIds = useMemo(() => absences.map((a) => a.id), [absences]);
  const affected = useAffectedLessons(absenceIds);
  const dates = useMemo(() => studyDates(from, to), [from, to]);
  const changes = useChangesForDates(dates);

  const changesById = useMemo(() => {
    const map = new Map<Uuid, ChangeDto>();
    for (const list of changes.data) for (const change of list ?? []) map.set(change.id, change);
    return map;
  }, [changes.data]);

  const entries = useMemo(() => {
    const result: LessonEntry[] = [];
    absences.forEach((absence, index) => {
      const items = (affected.data[index] ?? []).filter(
        (item) => compareIsoDates(item.date, from) >= 0 && compareIsoDates(item.date, to) <= 0,
      );
      result.push(...toEntries(absence, items, changesById));
    });
    return result;
  }, [absences, affected.data, changesById, from, to]);

  const groups = useMemo(() => groupByTeacher(entries), [entries]);

  const retry = useCallback(() => {
    void queryClient.refetchQueries({ queryKey: qk.admin.absences.all });
    void queryClient.refetchQueries({ queryKey: qk.admin.changes.all });
  }, [queryClient]);

  return {
    from,
    to,
    absences,
    entries,
    groups,
    changesById,
    loading: absencesQuery.isPending || affected.pending || changes.pending,
    error: absencesQuery.error ?? affected.error ?? changes.error ?? null,
    retry,
  };
}
