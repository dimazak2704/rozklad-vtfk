/** Версії розкладу: яку відкрити за замовчуванням, з якої дати публікувати, з чим порівнювати. */
import type { IsoDate, SemesterRef, TimetableVersionDto, Uuid } from '@/api/types';
import { addDays, compareIsoDates, formatNumericDate, isoWeekday } from '@/lib/date';
import { countLabel, PAIR_FORMS } from '@/lib/labels';
import { VERSION_SOURCE_LABEL, VERSION_STATUS_LABEL } from './labels';

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

/**
 * Один рядок контексту під заголовком: що це за версія. Числа якості (конфлікти, вікна) — у рядку
 * якості над сіткою, тут їх не повторюємо.
 * «Чернетка, 1 077 пар, імпорт з Excel» / «Опублікована, діє з 21.12.2026» / «Архів, 1 012 пар».
 */
export function versionSentence(version: TimetableVersionDto): string {
  const status = VERSION_STATUS_LABEL[version.status];
  if (version.status === 'PUBLISHED') {
    return version.effectiveFrom ? `${status}, діє з ${formatNumericDate(version.effectiveFrom)}` : `${status}, діє зараз`;
  }
  return `${status}, ${countLabel(version.lessonCount, PAIR_FORMS)}, ${VERSION_SOURCE_LABEL[version.source]}`;
}
