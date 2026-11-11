/** Мій день як речення: що я веду і коли наступна пара. Чиста логіка без React — для тестів. */
import type { DayDto, IsoDate, LessonView } from '@/api/types';
import { compareIsoDates, formatWeekdayDate, relativeDayLabel } from '@/lib/date';
import { roomLabel } from '@/lib/schedule';

/** «СТП, 4КІ2, ауд. 304» — що саме веде викладач. */
export function lessonPhrase(lesson: Pick<LessonView, 'discipline' | 'group' | 'room'>): string {
  return [lesson.discipline.shortName, lesson.group.code, roomLabel(lesson.room?.name)].filter(Boolean).join(', ');
}

export function ownLessons(day: DayDto | undefined, teacherId: string): LessonView[] {
  return (day?.lessons ?? []).filter((lesson) => lesson.teacher?.id === teacherId && lesson.kind !== 'CANCELLED').sort((a, b) => a.pair - b.pair);
}

/** «Завтра перша о 08:30 — ФВ, 4КІ2, с.з.» — перша пара наступного дня з парами в межах завантаженого тижня. */
export function nextDaySentence(days: readonly DayDto[], today: IsoDate, teacherId: string): string | null {
  const next = days
    .filter((day) => compareIsoDates(day.date, today) > 0)
    .sort((a, b) => compareIsoDates(a.date, b.date))
    .map((day) => ({ day, first: ownLessons(day, teacherId)[0] }))
    .find((entry) => entry.first);
  if (!next?.first) return null;
  const when = relativeDayLabel(next.day.date, today) ?? formatWeekdayDate(next.day.date);
  return `${when} перша о ${next.first.startsAt} — ${lessonPhrase(next.first)}.`;
}
