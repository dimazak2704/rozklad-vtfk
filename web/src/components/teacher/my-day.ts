/** Мій день як речення: що я веду і коли наступна пара. Чиста логіка без React — для тестів. */
import type { BellSlotDto, ChangeDto, DayDto, IsoDate, LessonView } from '@/api/types';
import {
  addDays,
  compareIsoDates,
  formatDayMonth,
  formatWeekdayDateAfterNa,
  isoWeekday,
  isWeekend,
  relativeDayLabel,
  timeToMinutes,
} from '@/lib/date';
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

/** Наступний після сьогодні день, де я веду хоч одну пару, і перша з них. */
export function nextOwnDay(days: readonly DayDto[], today: IsoDate, teacherId: string): { day: DayDto; first: LessonView } | null {
  const later = days.filter((day) => compareIsoDates(day.date, today) > 0).sort((a, b) => compareIsoDates(a.date, b.date));
  for (const day of later) {
    const first = ownLessons(day, teacherId)[0];
    if (first) return { day, first };
  }
  return null;
}

/**
 * Наступний день з моїми парами реченням: «Завтра перша о 08:30 — ФВ, 4КІ2, с.з.»,
 * «Завтра пар немає. У пʼятницю, 13 листопада, перша о 08:30 — СТП, 4КІ2, ауд. 14.» або
 * «У понеділок, 16 листопада, перша о 08:30 — …» (після пʼятниці «завтра пар немає» нічого не каже).
 * Шукає в переданих днях (поточний і, якщо треба, наступний тиждень); не знайшло — null.
 */
export function nextDaySentence(days: readonly DayDto[], today: IsoDate, teacherId: string): string | null {
  const next = nextOwnDay(days, today, teacherId);
  if (!next) return null;
  // пара із заміни без дзвінків дня — номером пари, а не «о null»
  const fact = next.first.startsAt
    ? `перша о ${next.first.startsAt} — ${lessonPhrase(next.first)}`
    : `перша — ${next.first.pair}\u00a0пара, ${lessonPhrase(next.first)}`;
  if (relativeDayLabel(next.day.date, today) === 'Завтра') return endSentence(`Завтра ${fact}`);
  const when = `У ${WEEKDAY_ACCUSATIVE[isoWeekday(next.day.date)] ?? ''}, ${formatDayMonth(next.day.date)}, ${fact}`;
  const tomorrow = addDays(today, 1);
  return endSentence(isWeekend(tomorrow) ? when : `Завтра пар немає. ${when}`);
}

/** Що показує годинник сьогодні: дзвінки дня і хвилини від півночі. */
export interface DayClock {
  bells: readonly BellSlotDto[];
  minutes: number;
}

/** Чи пара з `pair` уже скінчилася за дзвінками; без дзвінка на цю пару — ні (не знаємо). */
function pairOver(pair: number, clock: DayClock): boolean {
  const slot = clock.bells.find((s) => s.pair === pair);
  return slot ? timeToMinutes(slot.endsAt) <= clock.minutes : false;
}

/**
 * Найближчий запит на заміну (прохання CONFIRM), що ще чекає моєї відповіді: сьогодні чи пізніше.
 * Сьогоднішній запит на пару, що вже скінчилася, не рахуємо: відповідати на нього пізно, і «що далі»
 * не має вказувати на пару, яка минула.
 */
export function nextRequest(changes: readonly ChangeDto[], today: IsoDate, clock?: DayClock): ChangeDto | null {
  const open = changes.filter(
    (c) =>
      c.status === 'PENDING_CONFIRMATION' &&
      !c.supersededBy &&
      compareIsoDates(c.date, today) >= 0 &&
      !(clock && c.date === today && pairOver(c.pairTo, clock)),
  );
  return open.sort((a, b) => compareIsoDates(a.date, b.date) || a.pairFrom - b.pairFrom)[0] ?? null;
}

/**
 * Мої заміни на день, яких ще немає в опублікованому розкладі: я погодився (або мене повідомили, NOTIFY), але
 * розпорядження на цей день ще не опубліковане. Вести пару все одно мені, тож «зараз» має її знати.
 * Опубліковані (APPLIED) уже стоять серед моїх пар; відхилені й передані доповненням — не мої.
 */
export function confirmedAssignments(changes: readonly ChangeDto[], date: IsoDate): ChangeDto[] {
  return changes
    .filter((c) => c.date === date && c.status === 'CONFIRMED' && !c.supersededBy && c.teacherAnswer !== 'DECLINE' && !keepsOwnPair(c))
    .sort((a, b) => a.pairFrom - b.pairFrom);
}

/**
 * Зміна моєї ж пари, а не нова пара: інша аудиторія, інша дисципліна чи перестановка, де викладач лишається тим самим
 * (або нового не вказано), і скасування. Така пара вже стоїть у моєму розкладі — другою клітинкою «Заміна» вона не стає.
 */
function keepsOwnPair(change: ChangeDto): boolean {
  if (change.kind === 'CANCEL' || change.kind === 'DISCIPLINE_FINISHED') return true;
  const was = change.original.teacher;
  return was !== null && (change.new.teacher === null || change.new.teacher.id === was.id);
}

/** Пари, які займають заміни (BLOCK/ADDITIONAL — кілька поспіль). */
export function assignmentPairs(assignments: readonly ChangeDto[]): number[] {
  return assignments.flatMap((c) => Array.from({ length: Math.max(c.pairTo - c.pairFrom + 1, 1) }, (_, i) => c.pairFrom + i));
}

/** «заміна: СТП, 4КІ1, ауд. 14» — пара із заміни так само, як власна пара в реченні «зараз». */
export function assignmentPhrase(change: ChangeDto): string {
  const discipline = (change.new.discipline ?? change.original.discipline)?.shortName;
  const room = roomLabel((change.new.room ?? change.original.room)?.name);
  return `заміна: ${[discipline, change.group.code, room].filter(Boolean).join(', ')}`;
}

/**
 * Речення «що далі» в кінці дня разом із запитом на заміну, що чекає відповіді. Пари з запиту ще немає в моєму
 * розкладі, тож «Завтра пар немає» поруч із проханням на завтра звучало б як суперечність:
 * «Завтра пар немає, але є запит на заміну — 1 пара о 08:30. У понеділок, 16 листопада, перша о 09:40 — …».
 * Запит пізніше за мою наступну пару не згадуємо: до нього ще дійде черга, а список запитів стоїть одразу нижче.
 * `ownDate` — день моєї наступної пари (null — пар попереду немає), `startsAt` — початок пари з запиту.
 */
export function withRequest(
  next: string | null,
  request: ChangeDto | null,
  today: IsoDate,
  ownDate: IsoDate | null,
  startsAt?: string,
): string | null {
  if (!request) return next;
  const pair = `${request.pairFrom}\u00a0пара${startsAt ? ` о\u00a0${startsAt}` : ''}`;
  const relative = relativeDayLabel(request.date, today);
  const noneTomorrow = 'Завтра пар немає.';
  if (relative === 'Сьогодні') return [`Сьогодні ще є запит на заміну\u00a0— ${pair}.`, next].filter(Boolean).join(' ');
  if (relative === 'Завтра') {
    if (next?.startsWith(noneTomorrow)) return `Завтра пар немає, але є запит на заміну\u00a0— ${pair}.${next.slice(noneTomorrow.length)}`;
    if (next?.startsWith('Завтра ')) return `${next} Ще є запит на заміну\u00a0— ${pair}.`;
    return [`Завтра є запит на заміну\u00a0— ${pair}.`, next].filter(Boolean).join(' ');
  }
  if (ownDate && compareIsoDates(request.date, ownDate) > 0) return next;
  return [next, `Є запит на заміну на ${formatWeekdayDateAfterNa(request.date)}\u00a0— ${pair}.`].filter(Boolean).join(' ');
}

/** Вид заняття, яким заміна стоятиме в розкладі після публікації. */
function assignedKind(change: ChangeDto): LessonView['kind'] {
  if (change.kind === 'ADDITIONAL') return 'ADDITIONAL';
  if (change.kind === 'BLOCK') return 'BLOCK';
  return 'SUBSTITUTED';
}

/**
 * Заняття з підтвердженої, але ще не опублікованої заміни — так, як його покаже розклад після публікації:
 * «Заміна», закреслене «замість кого», група й аудиторія. BLOCK — одне заняття на кілька пар, решта — по одному на пару.
 * Час — з дзвінків дня; без них — null (клітинка покаже пару без часу).
 */
export function assignmentLessons(change: ChangeDto, bells?: readonly BellSlotDto[]): LessonView[] {
  const discipline = change.new.discipline ?? change.original.discipline;
  if (!discipline) return [];
  const block = change.kind === 'BLOCK';
  const pairs = block ? [change.pairFrom] : assignmentPairs([change]);
  return pairs.map((pair) => {
    const last = block ? change.pairTo : pair;
    return {
      id: `assigned-${change.id}-${pair}`,
      pair,
      startsAt: bells?.find((slot) => slot.pair === pair)?.startsAt ?? null,
      endsAt: bells?.find((slot) => slot.pair === last)?.endsAt ?? null,
      discipline,
      teacher: change.new.teacher,
      room: change.new.room ?? change.original.room,
      group: change.group,
      subgroup: change.subgroup,
      kind: assignedKind(change),
      online: false,
      original: { discipline: change.original.discipline, teacher: change.original.teacher, room: change.original.room, pair: null },
      reasonText: change.printedReason,
      changeId: change.id,
    };
  });
}

/**
 * Мій розклад разом із підтвердженими замінами, яких ще немає в опублікованому розпорядженні (`confirmedAssignments`).
 * Одне правило для всього кабінету: що «зараз» називає моєю парою, те стоїть і в сітці, і в денному списку, і в
 * переліку пар, які зачепить відсутність (DOMAIN §7: «lessons the teacher was to take over by a not yet published
 * change»). Заміна, що вже є серед занять дня (той самий `changeId`), не дублюється.
 */
export function withAssignments<T extends { days: DayDto[] }>(
  schedule: T,
  changes: readonly ChangeDto[],
  bellsByDate: ReadonlyMap<IsoDate, readonly BellSlotDto[]> = new Map(),
): T {
  let touched = false;
  const days = schedule.days.map((day) => {
    const known = new Set(day.lessons.map((lesson) => lesson.changeId).filter(Boolean));
    const extra = confirmedAssignments(changes, day.date)
      .filter((change) => !known.has(change.id))
      .flatMap((change) => assignmentLessons(change, bellsByDate.get(day.date)));
    if (extra.length === 0) return day;
    touched = true;
    return { ...day, lessons: [...day.lessons, ...extra].sort((a, b) => a.pair - b.pair || a.subgroup - b.subgroup) };
  });
  return touched ? { ...schedule, days } : schedule;
}
