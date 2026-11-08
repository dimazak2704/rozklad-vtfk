/**
 * Чиста логіка сторінок розкладу: підгрупи, перелік змін тижня, текст «Поділитися», зайнятість аудиторії.
 */
import type { BellSlotDto, DayDto, IsoDate, LessonView, PublicRoomDto, ScheduleDto, SemesterRef } from '@/api/types';
import { compareIsoDates, diffDays, formatWeekdayDate, startOfWeek } from '@/lib/date';
import { capitalize, PARITY_LABEL, REASON_CODE_HINT, ROOM_TYPE_LABEL } from '@/lib/labels';
import { isChangedLesson, lessonCounterpart, roomLabel, type Perspective } from '@/lib/schedule';

/** «Компʼютерний клас · 30 місць · корпус 2», «Спортзал · до 3 груп одночасно»; невідоме не показуємо. */
export function roomDetails(room: PublicRoomDto): string {
  return [
    capitalize(ROOM_TYPE_LABEL[room.type]),
    room.capacity ? `${room.capacity} місць` : null,
    room.maxParallel > 1 ? `до ${room.maxParallel} груп одночасно` : null,
    room.building ? `корпус: ${room.building}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
}

/** Номери підгруп, що трапляються в розкладі (для перемикача «Уся група · 1 п/г · 2 п/г»). */
export function subgroupsIn(schedule: ScheduleDto | undefined): number[] {
  const numbers = new Set<number>();
  for (const day of schedule?.days ?? []) for (const lesson of day.lessons) if (lesson.subgroup > 0) numbers.add(lesson.subgroup);
  return [...numbers].sort((a, b) => a - b);
}

/** Лише пари всієї групи та обраної підгрупи. */
export function filterBySubgroup(schedule: ScheduleDto, subgroup: number | null): ScheduleDto {
  if (subgroup === null) return schedule;
  return {
    ...schedule,
    days: schedule.days.map((day) => ({
      ...day,
      lessons: day.lessons.filter((lesson) => lesson.subgroup === 0 || lesson.subgroup === subgroup),
    })),
  };
}

export interface ChangedLesson {
  day: DayDto;
  lesson: LessonView;
}

/** Змінені заняття в порядку днів і пар. */
export function changedLessons(schedule: ScheduleDto | undefined): ChangedLesson[] {
  const out: ChangedLesson[] = [];
  for (const day of schedule?.days ?? []) {
    for (const lesson of [...day.lessons].sort((a, b) => a.pair - b.pair || a.subgroup - b.subgroup)) {
      if (isChangedLesson(lesson)) out.push({ day, lesson });
    }
  }
  return out;
}

function who(discipline: string | undefined | null, teacher: string | undefined | null): string {
  return [discipline, teacher].filter(Boolean).join(', ');
}

/** Що змінилося, одним рядком: «НДЕКСМ, Непийвода замість СТП, Кренцін». */
export function changeSummary(lesson: LessonView): string {
  const original = lesson.original;
  switch (lesson.kind) {
    case 'SUBSTITUTED': {
      const disciplineChanged = Boolean(original?.discipline && original.discipline.id !== lesson.discipline.id);
      const before = who(
        disciplineChanged ? original?.discipline?.shortName : null,
        original?.teacher && original.teacher.id !== lesson.teacher?.id ? original.teacher.displayName : null,
      );
      const after = who(disciplineChanged ? lesson.discipline.shortName : null, lesson.teacher?.displayName);
      return before ? `${after || lesson.discipline.shortName} замість ${before}` : `${lesson.discipline.shortName}: заміна`;
    }
    case 'CANCELLED':
      return `${lesson.discipline.shortName}: пари не буде`;
    case 'ADDITIONAL':
      return `додаткова пара: ${who(lesson.discipline.shortName, lesson.teacher?.displayName)}`;
    case 'ROOM_CHANGED':
      return `${lesson.discipline.shortName}: ${roomLabel(original?.room?.name) ?? 'аудиторія'} → ${roomLabel(lesson.room?.name) ?? '?'}`;
    case 'MOVED':
      return original?.pair ? `${lesson.discipline.shortName}: перенесено з ${original.pair} пари` : `${lesson.discipline.shortName}: перенесено`;
    case 'BLOCK':
      return `блок пар: ${lesson.discipline.shortName}`;
    case 'REGULAR':
      return lesson.discipline.shortName;
  }
}

/** Рядок заняття для тексту «Поділитися»: «2 пара, 09:40 — СТП, Кренцін, ауд. 301». */
function lessonLine(lesson: LessonView, perspective: Perspective): string {
  const counterpart = lessonCounterpart(lesson, perspective);
  const parts = [lesson.discipline.shortName, counterpart, roomLabel(lesson.room?.name)].filter(Boolean).join(', ');
  const subgroup = lesson.subgroup > 0 ? ` (${lesson.subgroup} п/г)` : '';
  const online = lesson.online ? ', онлайн' : '';
  let note = '';
  if (lesson.kind === 'CANCELLED') note = ' — пари не буде';
  else if (lesson.kind !== 'REGULAR') note = ` — ${changeSummary(lesson)}`;
  if (lesson.reasonText && lesson.kind !== 'REGULAR') note += ` (${lesson.reasonText})`;
  const at = lesson.startsAt ? `, ${lesson.startsAt}` : '';
  return `${lesson.pair} пара${at} — ${parts}${subgroup}${online}${note}`;
}

/**
 * Текст для Web Share: день, парність і всі пари з часом і змінами — щоб у чаті групи все було видно
 * навіть без переходу за посиланням.
 */
export function dayShareText(label: string, day: DayDto | undefined, perspective: Perspective): string {
  if (!day) return `Розклад ${label}`;
  const date = formatWeekdayDate(day.date).toLocaleLowerCase('uk');
  // поза семестром парності немає — не вигадуємо її в тексті
  const header = day.parity ? `${label} — ${date} (${PARITY_LABEL[day.parity]})` : `${label} — ${date}`;
  if (!day.studyDay) return `${header}: не навчальний день.`;
  const lessons = [...day.lessons].sort((a, b) => a.pair - b.pair || a.subgroup - b.subgroup);
  if (lessons.length === 0) return `${header}: пар немає.`;
  return [header, ...lessons.map((lesson) => lessonLine(lesson, perspective))].join('\n');
}

export function dayOf(schedule: ScheduleDto | undefined, date: IsoDate): DayDto | undefined {
  return schedule?.days.find((day) => day.date === date);
}

/** Пари, які на дату справді відбудуться (скасовані не рахуємо) — для лінії «зараз». */
export function activePairs(day: DayDto | undefined): number[] {
  return [...new Set((day?.lessons ?? []).filter((lesson) => lesson.kind !== 'CANCELLED').map((lesson) => lesson.pair))].sort(
    (a, b) => a - b,
  );
}

export interface Occupancy {
  /** Зайняті пари тижня (з урахуванням змін). */
  busy: number;
  /** Усі пари тижня за дзвінками. */
  total: number;
  /** Зайнятість по днях: номер пари → чи зайнята. */
  days: Array<{ date: IsoDate; weekday: number; pairs: Array<{ pair: number; busy: boolean }> }>;
}

/**
 * Зайнятість аудиторії: скільки пар тижня в ній проходять заняття. Скасовані й ті, що переїхали
 * в іншу аудиторію, не займають її.
 */
export function roomOccupancy(schedule: ScheduleDto, roomId: string, pairsPerWeekday: (weekday: number) => number): Occupancy {
  let busy = 0;
  let total = 0;
  const days = schedule.days
    .filter((day) => day.studyDay)
    .map((day) => {
      const count = pairsPerWeekday(day.weekday);
      const occupied = new Set(
        day.lessons.filter((lesson) => lesson.kind !== 'CANCELLED' && lesson.room?.id === roomId).map((lesson) => lesson.pair),
      );
      const pairs = Array.from({ length: count }, (_, index) => ({ pair: index + 1, busy: occupied.has(index + 1) }));
      busy += pairs.filter((p) => p.busy).length;
      total += count;
      return { date: day.date, weekday: day.weekday, pairs };
    });
  return { busy, total, days };
}

/**
 * Номер навчального тижня семестру для дати (тиждень 1 містить дату початку, DOMAIN §2) або null поза семестром.
 */
export function semesterWeekOf(date: IsoDate, semester: Pick<SemesterRef, 'startsOn' | 'endsOn'> | undefined | null): number | null {
  if (!semester) return null;
  const first = startOfWeek(semester.startsOn);
  if (compareIsoDates(startOfWeek(date), first) < 0 || compareIsoDates(date, semester.endsOn) > 0) return null;
  return Math.floor(diffDays(first, startOfWeek(date)) / 7) + 1;
}

/** «Відп.» → «Викладач відсутній»: розшифровка скорочень розпорядження (DOMAIN §7) для підказок. */
const REASON_BY_SHORT: Readonly<Record<string, string>> = {
  'Відп.': REASON_CODE_HINT.ABSENT,
  'Зв.зам.': REASON_CODE_HINT.MUTUAL,
  'Вичит.': REASON_CODE_HINT.FINISHED,
  'Відр.': REASON_CODE_HINT.BUSINESS_TRIP,
};

export function reasonHint(reason: string | null | undefined): string | null {
  if (!reason) return null;
  return REASON_BY_SHORT[reason.trim()] ?? null;
}

/** Час пари за дзвінками дня: «09:40–10:40» або null. */
export function pairTime(bells: readonly BellSlotDto[] | undefined, pair: number): string | null {
  const slot = bells?.find((s) => s.pair === pair);
  return slot ? `${slot.startsAt}–${slot.endsAt}` : null;
}
