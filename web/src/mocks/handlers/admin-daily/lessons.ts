/**
 * Заняття за опублікованим шаблоном (DOMAIN §6) з індексом за id. Шаблон незмінний, тож дні кешуються;
 * зміни накладаються окремо (rules.ts), а ці обʼєкти ніхто не змінює.
 */
import type { IsoDate, LessonView, Uuid } from '@/api/types';
import { addDays, compareIsoDates, isWeekend, nearestStudyDay, shiftStudyDay, todayInKyiv } from '@/lib/date';
import { ACTIVE_SEMESTER, isStudyDay, lessonsOn } from '../../fixtures';

const byDate = new Map<IsoDate, readonly LessonView[]>();
const byId = new Map<Uuid, { date: IsoDate; lesson: LessonView }>();
let semesterIndexed = false;

/** Усі заняття дати (усіх груп) за шаблоном, без змін. */
export function templateLessons(date: IsoDate): readonly LessonView[] {
  let lessons = byDate.get(date);
  if (!lessons) {
    lessons = lessonsOn(date);
    byDate.set(date, lessons);
    for (const lesson of lessons) byId.set(lesson.id, { date, lesson });
  }
  return lessons;
}

/** Заняття за id: спершу з уже порахованих днів, інакше індексуємо весь семестр (один раз). */
export function findTemplateLesson(id: Uuid): { date: IsoDate; lesson: LessonView } | undefined {
  const hit = byId.get(id);
  if (hit || semesterIndexed) return hit;
  for (let date = ACTIVE_SEMESTER.startsOn; compareIsoDates(date, ACTIVE_SEMESTER.endsOn) <= 0; date = addDays(date, 1)) {
    templateLessons(date);
  }
  semesterIndexed = true;
  return byId.get(id);
}

/** Навчальні дні з проміжку (включно). */
export function studyDaysBetween(from: IsoDate, to: IsoDate): IsoDate[] {
  const days: IsoDate[] = [];
  for (let date = from; compareIsoDates(date, to) <= 0; date = addDays(date, 1)) {
    if (isStudyDay(date)) days.push(date);
  }
  return days;
}

/**
 * День демо-ранку: найближчий навчальний день від «сьогодні» в Києві, але в межах семестру.
 * Тож демо видно на «Сьогодні» в будній день і на «Понеділок» у вихідні.
 */
export function demoMorningDate(now: Date = new Date()): IsoDate {
  const today = todayInKyiv(now);
  if (compareIsoDates(today, ACTIVE_SEMESTER.startsOn) < 0) return nearestStudyDay(ACTIVE_SEMESTER.startsOn);
  if (compareIsoDates(today, ACTIVE_SEMESTER.endsOn) > 0) {
    let last = ACTIVE_SEMESTER.endsOn;
    while (isWeekend(last)) last = addDays(last, -1);
    return last;
  }
  let date = nearestStudyDay(today);
  // свята й «без занять» теж пропускаємо, але не виходимо за кінець семестру
  while (!isStudyDay(date) && compareIsoDates(date, ACTIVE_SEMESTER.endsOn) < 0) date = shiftStudyDay(date, 1);
  return date;
}
