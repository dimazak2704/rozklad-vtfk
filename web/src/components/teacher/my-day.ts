/** Мій день як речення: що я веду і коли наступна пара. Чиста логіка без React — для тестів. */
import type { DayDto, IsoDate, LessonView } from '@/api/types';
import { addDays, compareIsoDates, formatDayMonth, isoWeekday, isWeekend, relativeDayLabel } from '@/lib/date';
import { WEEKDAY_ACCUSATIVE } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';

/** «СТП, 4КІ2, ауд. 304» — що саме веде викладач. */
export function lessonPhrase(lesson: Pick<LessonView, 'discipline' | 'group' | 'room'>): string {
  return [lesson.discipline.shortName, lesson.group.code, roomLabel(lesson.room?.name)].filter(Boolean).join(', ');
}

export function ownLessons(day: DayDto | undefined, teacherId: string): LessonView[] {
  return (day?.lessons ?? []).filter((lesson) => lesson.teacher?.id === teacherId && lesson.kind !== 'CANCELLED').sort((a, b) => a.pair - b.pair);
}

/** Крапка в кінці речення, якщо його не закінчує скорочення («с.з.»). */
export function endSentence(text: string): string {
  return /[.!?…]$/.test(text) ? text : `${text}.`;
}

/**
 * Наступний день з моїми парами реченням: «Завтра перша о 08:30 — ФВ, 4КІ2, с.з.»,
 * «Завтра пар немає. У пʼятницю, 13 листопада, перша о 08:30 — СТП, 4КІ2, ауд. 14.» або
 * «У понеділок, 16 листопада, перша о 08:30 — …» (після пʼятниці «завтра пар немає» нічого не каже).
 * Шукає в переданих днях (поточний і, якщо треба, наступний тиждень); не знайшло — null.
 */
export function nextDaySentence(days: readonly DayDto[], today: IsoDate, teacherId: string): string | null {
  const next = days
    .filter((day) => compareIsoDates(day.date, today) > 0)
    .sort((a, b) => compareIsoDates(a.date, b.date))
    .map((day) => ({ day, first: ownLessons(day, teacherId)[0] }))
    .find((entry) => entry.first);
  if (!next?.first) return null;
  const fact = `перша о ${next.first.startsAt} — ${lessonPhrase(next.first)}`;
  if (relativeDayLabel(next.day.date, today) === 'Завтра') return endSentence(`Завтра ${fact}`);
  const when = `У ${WEEKDAY_ACCUSATIVE[isoWeekday(next.day.date)] ?? ''}, ${formatDayMonth(next.day.date)}, ${fact}`;
  const tomorrow = addDays(today, 1);
  return endSentence(isWeekend(tomorrow) ? when : `Завтра пар немає. ${when}`);
}
