/** Версії розкладу: яку відкрити за замовчуванням, з якої дати публікувати, з чим порівнювати. */
import type { IsoDate, SemesterRef, TimetableVersionDto, Uuid } from '@/api/types';
import { addDays, compareIsoDates, isoWeekday } from '@/lib/date';

/**
 * Версія за замовчуванням: та, що в адресі (якщо є серед версій семестру), інакше найсвіжіша чернетка —
 * над нею зазвичай і працюють, — інакше опублікована, інакше перша.
 */
export function pickVersion(versions: readonly TimetableVersionDto[], requested: Uuid | null): TimetableVersionDto | null {
  if (requested) {
    const exact = versions.find((v) => v.id === requested);
    if (exact) return exact;
  }
  return versions.find((v) => v.status === 'DRAFT') ?? versions.find((v) => v.status === 'PUBLISHED') ?? versions[0] ?? null;
}

export function publishedVersion(versions: readonly TimetableVersionDto[]): TimetableVersionDto | null {
  return versions.find((v) => v.status === 'PUBLISHED') ?? null;
}

/** Найближчий понеділок від завтра (розклад зазвичай вводять з нового тижня), у межах семестру. */
export function defaultEffectiveFrom(today: IsoDate, semester: SemesterRef): IsoDate {
  let date = addDays(today, 1);
  while (isoWeekday(date) !== 1) date = addDays(date, 1);
  if (compareIsoDates(date, semester.startsOn) < 0) return semester.startsOn;
  if (compareIsoDates(date, semester.endsOn) > 0) return compareIsoDates(today, semester.endsOn) <= 0 ? today : semester.endsOn;
  return date;
}
