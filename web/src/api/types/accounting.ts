import type { AccountingStatus, DisciplineRef, GroupRef, IsoDate, Nullable, PeriodEffect, PlanSource, TeacherRef, Uuid } from './common';

// ───────────────────────── 2. Admin: облік годин ─────────────────────────

/** @inferred — формат пропозиції контракт не описує */
export interface AccountingSuggestion {
  kind: 'DISCIPLINE_FINISHED' | 'ADDITIONAL';
  text: string;
  fromDate?: Nullable<IsoDate>;
  lessons?: Nullable<number>;
}

/** GET /api/admin/accounting?semesterId&groupId */
export interface AccountingRowDto {
  group: GroupRef;
  discipline: DisciplineRef;
  plan: number;
  fact: number;
  remaining: number;
  forecast: number;
  status: AccountingStatus;
  forecastFinishDate?: Nullable<IsoDate>;
  suggestion?: Nullable<AccountingSuggestion>;
  /** Сума годин на тиждень записів навантаження дисципліни. */
  hoursPerWeek: number;
  /** Звідки план: AUTO — рахує система, FIXED — задане число, MIXED — записи різні. */
  planSource: PlanSource | 'MIXED';
}

/**
 * GET /api/admin/accounting/theory: скільки тижнів група на теорії в семестрі системи (рішення власника
 * 15.11.2026: тижні НС, практики, сесії, зборів і канікул не рахуються й не надолужуються) і що її знімає.
 */
export interface GroupTheoryDto {
  group: GroupRef;
  /** Тижні семестру (від понеділка). */
  semesterWeeks: number;
  /** Тижні, у яких група має хоч один навчальний день. */
  theoryWeeks: number;
  numeratorWeeks: number;
  denominatorWeeks: number;
  studyDays: number;
  /** Тижні теорії з графіка освітнього процесу — лише для звірки; null, якщо графік не імпортовано. */
  graphTheoryWeeks: Nullable<number>;
  offPeriods: GroupOffPeriodDto[];
}

/** Період, що знімає групу з пар: код виду — як у графіку (НС, НП, К…). */
export interface GroupOffPeriodDto {
  id: Nullable<Uuid>;
  kind: { code: string; name: string; color: Nullable<string>; effect: PeriodEffect };
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
}

/** GET /api/admin/accounting/teachers?month=2026-10 */
export interface TeacherStatsDto {
  teacher: TeacherRef;
  substitutionsCount: number;
  substitutionsHours: number;
  missedCount: number;
}
