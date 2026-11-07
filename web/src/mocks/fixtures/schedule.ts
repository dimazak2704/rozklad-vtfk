/**
 * Генератор ScheduleDto для мок-API: матеріалізує опублікований шаблон на дати (DOMAIN §6) —
 * парність тижня, практика групи прибирає її заняття, дистанційний режим позначає заняття online.
 * Детермінований: ті самі вхідні дані дають ті самі id і порядок.
 */
import type {
  DayDto,
  EntityType,
  IsoDate,
  LessonView,
  ScheduleDto,
  ScheduleOwner,
  TemplateLessonDto,
  Uuid,
  Weekday,
} from '@/api/types';
import { addDays, compareIsoDates, diffDays, isIsoDate, isoWeekday } from '@/lib/date';
import { weekdayName } from '@/lib/labels';
import { mockId } from '../ids';
import { bellSlots, isStudyDay, parityOn, periodsOn } from './calendar';
import { findGroup, findRoom, findTeacher } from './catalog';
import { TEMPLATE_LESSONS } from './timetable';

/** Контракт: розклад не більше ніж на 31 день (CONTRACTS §1). */
export const MAX_SCHEDULE_DAYS = 31;

export interface ScheduleOptions {
  /**
   * Зміни поверх шаблону: отримує всі заняття дати (усіх груп) і повертає новий список, тож заміна
   * видна і групі, і обом викладачам. Області «Заміни» й «Розпорядження» підключають сюди свої сховища.
   */
  overlay?: (date: IsoDate, lessons: LessonView[]) => LessonView[];
  /** Інший шаблон (наприклад, чернетка версії) замість опублікованого. */
  template?: readonly TemplateLessonDto[];
}

const UK = new Intl.Collator('uk');

/** «ПОНЕДІЛОК», «ПʼЯТНИЦЯ» — як у заголовку розпорядження. */
export function weekdayLabel(date: IsoDate): string {
  return weekdayName(isoWeekday(date)).toLocaleUpperCase('uk');
}

function compareLessons(a: LessonView, b: LessonView): number {
  return a.pair - b.pair || a.subgroup - b.subgroup || UK.compare(a.group.code, b.group.code);
}

/** Усі заняття дати (усіх груп): шаблон за днем тижня й парністю, без практики, з дистанційним режимом. */
export function lessonsOn(date: IsoDate, options: ScheduleOptions = {}): LessonView[] {
  if (!isStudyDay(date)) return [];
  const weekday = isoWeekday(date) as Weekday;
  const parity = parityOn(date);
  const bells = new Map(bellSlots(weekday).map((slot) => [slot.pair, slot]));
  const lessons: LessonView[] = [];
  for (const template of options.template ?? TEMPLATE_LESSONS) {
    if (template.weekday !== weekday || (template.parity !== 'BOTH' && template.parity !== parity)) continue;
    const group = findGroup(template.group.id);
    if (!group) throw new Error(`Мок-дані неузгоджені: невідома група ${template.group.code}`);
    const periods = periodsOn(date, group);
    // DOMAIN §2: практика групи прибирає її заняття
    if (periods.some((p) => p.kind === 'PRACTICE')) continue;
    const bell = bells.get(template.pair);
    if (!bell) throw new Error(`Мок-дані неузгоджені: немає дзвінка на ${template.pair} пару (день ${weekday})`);
    lessons.push({
      id: mockId('lesson', `${template.id}@${date}`),
      pair: template.pair,
      startsAt: bell.startsAt,
      endsAt: bell.endsAt,
      discipline: template.discipline,
      teacher: template.teacher ?? null,
      room: template.room ?? null,
      group: template.group,
      subgroup: template.subgroup,
      kind: 'REGULAR',
      online: template.online || periods.some((p) => p.kind === 'STUDY_MODE' && p.studyMode === 'ONLINE'),
      original: null,
      reasonText: null,
      changeId: null,
    });
  }
  const result = options.overlay ? options.overlay(date, lessons) : lessons;
  return [...result].sort(compareLessons);
}

/** Чий розклад: група за id, викладач, аудиторія. null — такого власника немає. */
export function scheduleOwner(type: EntityType, id: Uuid): ScheduleOwner | null {
  switch (type) {
    case 'GROUP': {
      const group = findGroup(id);
      return group ? { type, id, label: group.code } : null;
    }
    case 'TEACHER': {
      const teacher = findTeacher(id);
      return teacher ? { type, id, label: teacher.shortName } : null;
    }
    case 'ROOM': {
      const room = findRoom(id);
      return room ? { type, id, label: room.name } : null;
    }
  }
}

/** Заняття власника; заміщений викладач чи звільнена аудиторія теж бачать змінене заняття. */
function belongsTo(lesson: LessonView, owner: ScheduleOwner): boolean {
  switch (owner.type) {
    case 'GROUP':
      return lesson.group.id === owner.id;
    case 'TEACHER':
      return lesson.teacher?.id === owner.id || lesson.original?.teacher?.id === owner.id;
    case 'ROOM':
      return lesson.room?.id === owner.id || lesson.original?.room?.id === owner.id;
  }
}

/** Примітка дня групи з календарних періодів: практика, дистанційний режим. */
function groupDayNote(date: IsoDate, groupId: Uuid): string | null {
  const group = findGroup(groupId);
  const titles = periodsOn(date, group)
    .filter((p) => p.kind === 'PRACTICE' || p.kind === 'STUDY_MODE')
    .map((p) => p.title);
  return titles.length > 0 ? titles.join('; ') : null;
}

/** Помилка параметрів from/to для 400 (українською) або null, якщо все гаразд. */
export function scheduleRangeError(from: string | null, to: string | null): string | null {
  if (!from || !to) return 'Вкажіть дати from і to у форматі РРРР-ММ-ДД.';
  if (!isIsoDate(from) || !isIsoDate(to)) return 'Дати мають бути у форматі РРРР-ММ-ДД.';
  if (compareIsoDates(from, to) > 0) return 'Дата from не може бути пізніше за to.';
  if (diffDays(from, to) + 1 > MAX_SCHEDULE_DAYS) return `Розклад можна отримати не більше ніж на ${MAX_SCHEDULE_DAYS} день.`;
  return null;
}

/**
 * Розклад групи, викладача чи аудиторії на проміжок дат (лише пн–пт: DayDto.weekday 1..5).
 * Повертає null для невідомого власника; межі дат перевіряє scheduleRangeError.
 */
export function buildSchedule(
  type: EntityType,
  id: Uuid,
  from: IsoDate,
  to: IsoDate,
  options: ScheduleOptions = {},
): ScheduleDto | null {
  const owner = scheduleOwner(type, id);
  if (!owner) return null;
  const days: DayDto[] = [];
  for (let date = from; compareIsoDates(date, to) <= 0; date = addDays(date, 1)) {
    const weekday = isoWeekday(date);
    if (weekday > 5) continue;
    const studyDay = isStudyDay(date);
    days.push({
      date,
      weekday: weekday as Weekday,
      weekdayLabel: weekdayLabel(date),
      parity: parityOn(date),
      studyDay,
      note: owner.type === 'GROUP' ? groupDayNote(date, owner.id) : null,
      lessons: studyDay ? lessonsOn(date, options).filter((lesson) => belongsTo(lesson, owner)) : [],
    });
  }
  return { owner, from, to, days };
}
