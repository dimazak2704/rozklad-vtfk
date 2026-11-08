/**
 * ДЕМО-ранок диспетчера (синтетичні відсутності й рішення на справжньому розкладі): кілька відсутніх
 * викладачів, частина пар уже закрита, одна заміна чекає відповіді й прострочена, одна свіжа, одна відмова,
 * решта без рішення. День — найближчий навчальний від «сьогодні», тож демо видно в будь-який день семестру.
 * Сценарій повторює розпорядження №35: Крисак Л. на лікарняному (Відп.), підгрупова пара — «Пари не буде».
 */
import type { AbsenceDto, AbsenceReasonCategory, AbsenceSource, IsoDate, LessonView, TeacherDto } from '@/api/types';
import { compareIsoDates, shiftStudyDay } from '@/lib/date';
import { ACTIVE_SEMESTER, teacherNamed, teacherRef } from '../../fixtures';
import { mockId } from '../../ids';
import { makeChange, transition } from './factory';
import { demoMorningDate, templateLessons } from './lessons';
import { order35 } from './orders';
import { affectedLessons, optionsFor } from './rules';
import { ADMIN_ACTOR, emptyWorld, type ChangeRecord, type World } from './world';

/** Налаштування, з якими «створювалися» демо-рішення (як типові в settings.ts). */
const SEED_APPROVAL = { mode: 'CONFIRM', timeoutMinutes: 15 } as const;

const minutesAgo = (now: Date, minutes: number) => new Date(now.getTime() - minutes * 60_000);

function lessonsOfTeacher(teacher: TeacherDto, date: IsoDate): LessonView[] {
  return templateLessons(date).filter((lesson) => lesson.teacher?.id === teacher.id);
}

/** Перший викладач зі списку, у якого цього дня щонайменше `minLessons` пар. */
function pickTeacher(names: readonly string[], date: IsoDate, exclude: readonly TeacherDto[], minLessons = 2): TeacherDto | null {
  for (const name of names) {
    const teacher = teacherNamed(name);
    if (exclude.some((t) => t.id === teacher.id)) continue;
    if (lessonsOfTeacher(teacher, date).length >= minLessons) return teacher;
  }
  return null;
}

function addAbsence(
  world: World,
  input: {
    key: string;
    teacher: TeacherDto;
    dateFrom: IsoDate;
    dateTo: IsoDate;
    pairs: number[] | null;
    reason: AbsenceReasonCategory;
    source: AbsenceSource;
    at: Date;
  },
): AbsenceDto {
  const absence: AbsenceDto = {
    id: mockId('absence', `demo:${input.key}:${input.dateFrom}`),
    teacher: teacherRef(input.teacher),
    dateFrom: input.dateFrom,
    dateTo: input.dateTo,
    pairs: input.pairs,
    reasonCategory: input.reason,
    comment: null,
    source: input.source,
    status: 'ACTIVE',
    createdAt: input.at.toISOString(),
  };
  world.absences.set(absence.id, absence);
  return absence;
}

/** Рішення з найкращим кандидатом-викладачем (як обрав би диспетчер). */
function replaceWithTop(world: World, date: IsoDate, lesson: LessonView, absence: AbsenceDto, key: string, at: Date): ChangeRecord | null {
  const top = optionsFor(world, date, lesson).find((option) => option.kind === 'REPLACE_TEACHER');
  if (!top?.teacher) return null;
  const change = makeChange(
    {
      id: mockId('change', `demo:${key}:${date}`),
      kind: 'REPLACE_TEACHER',
      date,
      group: lesson.group,
      subgroup: lesson.subgroup,
      pairFrom: lesson.pair,
      pairTo: null,
      lesson,
      newTeacher: top.teacher,
      reasonCode: absence.reasonCategory === 'BUSINESS_TRIP' ? 'BUSINESS_TRIP' : 'ABSENT',
      absenceId: absence.id,
    },
    { at, actor: ADMIN_ACTOR, ...SEED_APPROVAL },
  );
  world.changes.set(change.id, change);
  return change;
}

function cancelLesson(world: World, date: IsoDate, lesson: LessonView, absence: AbsenceDto, key: string, at: Date): void {
  const change = makeChange(
    {
      id: mockId('change', `demo:${key}:${date}`),
      kind: 'CANCEL',
      date,
      group: lesson.group,
      subgroup: lesson.subgroup,
      pairFrom: lesson.pair,
      pairTo: null,
      lesson,
      reasonCode: 'ABSENT',
      absenceId: absence.id,
    },
    { at, actor: ADMIN_ACTOR, ...SEED_APPROVAL },
  );
  world.changes.set(change.id, change);
}

/** Відповідь викладача в Telegram (як її записує core після виклику бота). */
function reply(world: World, change: ChangeRecord | null, decision: 'CONFIRMED' | 'DECLINED', at: Date, comment: string | null = null): void {
  if (!change) return;
  const actor = change.new.teacher?.shortName ?? 'telegram';
  world.changes.set(change.id, transition(change, decision, at, actor, comment, { respondedAt: at.toISOString() }));
}

function clampToSemester(date: IsoDate): IsoDate {
  return compareIsoDates(date, ACTIVE_SEMESTER.endsOn) > 0 ? ACTIVE_SEMESTER.endsOn : date;
}

export function seedWorld(now: Date): World {
  const world = emptyWorld();
  const archived = order35();
  world.orders.set(archived.id, archived);

  const date = demoMorningDate(now);

  // 1. Лікарняний на три навчальні дні, повідомлений через бота (як Крисак Л. у №35)
  const krysak = teacherNamed('Крисак Л.В.');
  const sick = addAbsence(world, {
    key: 'krysak',
    teacher: krysak,
    dateFrom: date,
    dateTo: clampToSemester(shiftStudyDay(shiftStudyDay(date, 1), 1)),
    pairs: null,
    reason: 'SICK_LEAVE',
    source: 'BOT',
    at: minutesAgo(now, 74),
  });
  const sickLessons = affectedLessons(world, sick)
    .filter((item) => item.date === date)
    .map((item) => item.lesson);
  const [first, second, third] = sickLessons;
  if (first) {
    if (first.subgroup > 0) cancelLesson(world, date, first, sick, 'krysak-1', minutesAgo(now, 66));
    else reply(world, replaceWithTop(world, date, first, sick, 'krysak-1', minutesAgo(now, 66)), 'CONFIRMED', minutesAgo(now, 58));
  }
  if (second) reply(world, replaceWithTop(world, date, second, sick, 'krysak-2', minutesAgo(now, 63)), 'CONFIRMED', minutesAgo(now, 47));
  // запит без відповіді довше за тайм-аут: на панелі — «прострочено», диспетчер може нагадати
  if (third) replaceWithTop(world, date, third, sick, 'krysak-3', minutesAgo(now, 22));

  // 2. Відрядження на день: перший кандидат відмовився, друга пара чекає відповіді
  const travelling = pickTeacher(['Кренцін', 'Охота О.Я.', 'Масловата Д.Р.', 'Вернигора', 'Погосян'], date, [krysak]);
  if (travelling) {
    const trip = addAbsence(world, {
      key: 'trip',
      teacher: travelling,
      dateFrom: date,
      dateTo: date,
      pairs: null,
      reason: 'BUSINESS_TRIP',
      source: 'ADMIN',
      at: minutesAgo(now, 51),
    });
    const [tripFirst, tripSecond] = affectedLessons(world, trip).map((item) => item.lesson);
    if (tripFirst) {
      reply(world, replaceWithTop(world, date, tripFirst, trip, 'trip-1', minutesAgo(now, 44)), 'DECLINED', minutesAgo(now, 31), 'Не можу цього дня');
    }
    if (tripSecond) replaceWithTop(world, date, tripSecond, trip, 'trip-2', minutesAgo(now, 4));
  }

  // 3. Відсутність на останні дві пари, повідомлена викладачем у кабінеті — ще без рішень
  const leaving = pickTeacher(['Масловата Д.Р.', 'Охота О.Я.', 'Вернигора', 'Погосян', 'Кренцін'], date, travelling ? [krysak, travelling] : [krysak]);
  if (leaving) {
    const pairs = [...new Set(lessonsOfTeacher(leaving, date).map((lesson) => lesson.pair))].sort((a, b) => a - b).slice(-2);
    addAbsence(world, {
      key: 'leaving',
      teacher: leaving,
      dateFrom: date,
      dateTo: date,
      pairs,
      reason: 'PERSONAL',
      source: 'WEB',
      at: minutesAgo(now, 18),
    });
  }

  return world;
}
