/**
 * Мок області «Базовий розклад» (CONTRACTS §2 Timetable): версії, заняття шаблону, конфлікти (DOMAIN §5),
 * метрики, порівняння й публікація (DOMAIN §6). Опублікована версія — справжній шаблон з Excel (фікстури).
 *
 * Тут же — правила, за якими мок рахує конфлікти й метрики; їх використовує й генератор (handlers/generation.ts).
 */
import { http, HttpResponse } from 'msw';
import type { LessonMutationResult, TimetableConflict, TimetableConflictsDto } from '@/api/timetable';
import type {
  Conflict,
  CreateVersionRequest,
  DisciplineDto,
  FieldErrorDto,
  GroupDto,
  IsoDate,
  LessonKind,
  MetricsDto,
  MoveLessonRequest,
  Parity,
  PublishVersionRequest,
  PublishVersionResponse,
  RoomDto,
  TemplateLessonDto,
  TemplateLessonRequest,
  TimetableVersionDto,
  TimetableVersionSource,
  TimetableVersionStatus,
  Uuid,
  VersionDiffDto,
  WeekParity,
  Weekday,
} from '@/api/types';
import { addDays, compareIsoDates, formatNumericDate, isIsoDate, isoWeekday, todayInKyiv } from '@/lib/date';
import { countLabel, PAIR_FORMS, weekdayName } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import { matchScore } from '@/lib/search';
import { emitServerEvent } from '../events';
import {
  ACTIVE_SEMESTER,
  DISCIPLINES,
  disciplineRef,
  findDiscipline,
  findGroup,
  findRoom,
  findTeacher,
  GROUPS,
  groupRef,
  isStudyDay,
  parityOn,
  periodsOn,
  PUBLISHED_VERSION_EFFECTIVE_FROM,
  PUBLISHED_VERSION_ID,
  ROOMS,
  roomRef,
  SEMESTERS,
  SHIFT_WINDOWS,
  teacherRef,
  TEACHERS,
  TEMPLATE_LESSONS,
  toPublicGroup,
  toPublicRoom,
  toPublicTeacher,
} from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, businessRule, conflict, notFound, paginate, readJson } from '../kit';
import { createMockStore } from '../store';

// ───────────────────────── Правила слотів (DOMAIN §5) ─────────────────────────

export const WEEK_PARITIES: readonly WeekParity[] = ['NUMERATOR', 'DENOMINATOR'];
const PARITY_VALUES: readonly Parity[] = ['BOTH', 'NUMERATOR', 'DENOMINATOR'];
const LESSON_KINDS: readonly LessonKind[] = ['LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED'];
const PARITY_TEXT: Record<WeekParity, string> = { NUMERATOR: 'чисельник', DENOMINATOR: 'знаменник' };

/** Пізня пара — після 16:00 (7-ма і 8-ма за профілем «2 зміни, 60 хв»). */
export const LATE_PAIR_FROM = 7;
/** Більше стількох пар на день — перевантаження (DOMAIN §5). */
const MAX_COMFORT_PAIRS = 4;
/** Дисципліна стільки разів за день — повтор (DOMAIN §5). */
const DISCIPLINE_REPEAT_FROM = 3;

/** Чи проходить заняття в тиждень `week`. */
export const runsIn = (parity: Parity, week: WeekParity): boolean => parity === 'BOTH' || parity === week;
/** BOTH перетинається з усім; чисельник і знаменник — ні. */
export const paritiesOverlap = (a: Parity, b: Parity): boolean => a === 'BOTH' || b === 'BOTH' || a === b;
/** Уся група (0) перетинається з будь-якою підгрупою; підгрупа k — з 0 і з k. */
export const cohortsOverlap = (a: number, b: number): boolean => a === 0 || b === 0 || a === b;
const sameStream = (a: TemplateLessonDto, b: TemplateLessonDto): boolean => a.streamId != null && a.streamId === b.streamId;

const groupsById: ReadonlyMap<Uuid, GroupDto> = new Map(GROUPS.map((group) => [group.id, group]));
const roomsById: ReadonlyMap<Uuid, RoomDto> = new Map(ROOMS.map((room) => [room.id, room]));

/** Вікно зміни групи на день тижня (shift_window). */
export function shiftWindowOf(group: GroupDto | undefined, weekday: number): { firstPair: number; lastPair: number } | null {
  if (!group) return null;
  return SHIFT_WINDOWS.find((window) => window.shift === group.shift && window.weekday === weekday) ?? null;
}

export function groupOf(lesson: TemplateLessonDto): GroupDto | undefined {
  return groupsById.get(lesson.group.id);
}

export function maxParallelOf(roomId: Uuid): number {
  return roomsById.get(roomId)?.maxParallel ?? 1;
}

// ───────────────────────── Тексти повідомлень ─────────────────────────

const slotText = (weekday: number, pair: number) => `${weekdayName(weekday)}, ${pair} пара`;
const cohortText = (lesson: TemplateLessonDto) =>
  lesson.subgroup > 0 ? `${lesson.group.code} (${lesson.subgroup} п/г)` : lesson.group.code;
const lessonTag = (lesson: TemplateLessonDto) => `${cohortText(lesson)} — ${lesson.discipline.shortName}`;
const roomText = (name: string) => roomLabel(name) ?? name;
const ordinal = (pair: number) => `${pair}-ї`;

function joinAnd(items: readonly string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} і ${items.at(-1) ?? ''}`;
}

function weeksText(weeks: readonly WeekParity[]): string {
  return weeks.length === 1 && weeks[0] ? ` (${PARITY_TEXT[weeks[0]]})` : '';
}

// ───────────────────────── Жорсткі конфлікти ─────────────────────────

function groupBy<T>(items: readonly T[], key: (item: T) => string | null): Map<string, T[]> {
  const map = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    if (k === null) continue;
    const list = map.get(k);
    if (list) list.push(item);
    else map.set(k, [item]);
  }
  return map;
}

/** Усі учасники хоча б однієї пари, що «зіткнулася». */
function clashing(list: readonly TemplateLessonDto[], clash: (a: TemplateLessonDto, b: TemplateLessonDto) => boolean) {
  const involved = new Set<TemplateLessonDto>();
  for (let i = 0; i < list.length; i += 1) {
    for (let j = i + 1; j < list.length; j += 1) {
      const a = list[i] as TemplateLessonDto;
      const b = list[j] as TemplateLessonDto;
      if (clash(a, b)) {
        involved.add(a);
        involved.add(b);
      }
    }
  }
  return [...involved];
}

function hardConflict(kind: Conflict['kind'], message: string, lessons: readonly TemplateLessonDto[], weekday: Weekday, pair: number): Conflict {
  return { kind, severity: 'HARD', message, lessonIds: lessons.map((l) => l.id), weekday, pair };
}

/** Заняття потоку займають аудиторію один раз. */
function roomUnits(list: readonly TemplateLessonDto[]): TemplateLessonDto[][] {
  const units = groupBy(list, (lesson) => lesson.streamId ?? lesson.id);
  return [...units.values()];
}

function findHardConflicts(lessons: readonly TemplateLessonDto[]): Conflict[] {
  const out: Conflict[] = [];
  for (const slot of groupBy(lessons, (l) => `${l.weekday}|${l.pair}`).values()) {
    const { weekday, pair } = slot[0] as TemplateLessonDto;

    for (const list of groupBy(slot, (l) => l.teacher?.id ?? null).values()) {
      const involved = clashing(list, (a, b) => paritiesOverlap(a.parity, b.parity) && !sameStream(a, b));
      if (involved.length === 0) continue;
      const teacher = involved[0]?.teacher?.shortName ?? '';
      out.push(
        hardConflict('TEACHER', `${teacher} веде одночасно ${joinAnd(involved.map(lessonTag))} — ${slotText(weekday, pair)}`, involved, weekday, pair),
      );
    }

    for (const list of groupBy(slot, (l) => l.group.id).values()) {
      const involved = clashing(list, (a, b) => paritiesOverlap(a.parity, b.parity) && cohortsOverlap(a.subgroup, b.subgroup));
      if (involved.length === 0) continue;
      const names = involved.map((l) => (l.subgroup > 0 ? `${l.discipline.shortName} (${l.subgroup} п/г)` : l.discipline.shortName));
      out.push(
        hardConflict('GROUP', `${involved[0]?.group.code ?? ''}: одночасно ${joinAnd(names)} — ${slotText(weekday, pair)}`, involved, weekday, pair),
      );
    }

    for (const [roomId, list] of groupBy(slot, (l) => l.room?.id ?? null)) {
      const capacity = maxParallelOf(roomId);
      const units = roomUnits(list);
      const overloaded = WEEK_PARITIES.filter(
        (week) => units.filter((unit) => runsIn((unit[0] as TemplateLessonDto).parity, week)).length > capacity,
      );
      if (overloaded.length === 0) continue;
      const involved = units.filter((unit) => overloaded.some((week) => runsIn((unit[0] as TemplateLessonDto).parity, week))).flat();
      const name = roomText(involved[0]?.room?.name ?? '');
      const message =
        capacity > 1
          ? `${name} приймає до ${capacity} груп одночасно, а тут більше: ${joinAnd(involved.map(lessonTag))} — ${slotText(weekday, pair)}`
          : `${name} зайнята двічі: ${joinAnd(involved.map(lessonTag))} — ${slotText(weekday, pair)}`;
      out.push(hardConflict('ROOM', message, involved, weekday, pair));
    }
  }

  for (const lesson of lessons) {
    const group = groupOf(lesson);
    const window = shiftWindowOf(group, lesson.weekday);
    if (!group || (window && lesson.pair >= window.firstPair && lesson.pair <= window.lastPair)) continue;
    const range = window ? `пари ${window.firstPair}–${window.lastPair}` : 'у цей день пар немає';
    out.push(
      hardConflict(
        'SHIFT',
        `${lesson.group.code}: ${lesson.pair} пара поза вікном ${group.shift} зміни (${range}) — ${weekdayName(lesson.weekday)}`,
        [lesson],
        lesson.weekday,
        lesson.pair,
      ),
    );
  }
  return out;
}

// ───────────────────────── Профілі днів і мʼякі попередження ─────────────────────────

/** Пари, на яких власник (група чи викладач) має заняття, окремо для чисельника і знаменника. */
export interface DayPresence {
  weeks: Record<WeekParity, Map<number, TemplateLessonDto[]>>;
  lessons: TemplateLessonDto[];
}

function emptyDay(): DayPresence {
  return { weeks: { NUMERATOR: new Map(), DENOMINATOR: new Map() }, lessons: [] };
}

/** owner → weekday → присутність. */
export function presenceBy(
  lessons: readonly TemplateLessonDto[],
  ownerOf: (lesson: TemplateLessonDto) => string | null,
): Map<string, Map<number, DayPresence>> {
  const out = new Map<string, Map<number, DayPresence>>();
  for (const lesson of lessons) {
    const owner = ownerOf(lesson);
    if (owner === null) continue;
    let days = out.get(owner);
    if (!days) {
      days = new Map();
      out.set(owner, days);
    }
    let day = days.get(lesson.weekday);
    if (!day) {
      day = emptyDay();
      days.set(lesson.weekday, day);
    }
    day.lessons.push(lesson);
    for (const week of WEEK_PARITIES) {
      if (!runsIn(lesson.parity, week)) continue;
      const list = day.weeks[week].get(lesson.pair);
      if (list) list.push(lesson);
      else day.weeks[week].set(lesson.pair, [lesson]);
    }
  }
  return out;
}

export function sortedPairs(map: ReadonlyMap<number, unknown>): number[] {
  return [...map.keys()].sort((a, b) => a - b);
}

/** «Вікна»: порожні пари між першою й останньою парою дня (окремо в кожен тиждень), обʼєднані по клітинках. */
export function windowCells(day: DayPresence): Array<{ pair: number; weeks: WeekParity[] }> {
  const cells = new Map<number, WeekParity[]>();
  for (const week of WEEK_PARITIES) {
    const pairs = sortedPairs(day.weeks[week]);
    const first = pairs[0];
    const last = pairs.at(-1);
    if (first === undefined || last === undefined) continue;
    for (let pair = first + 1; pair < last; pair += 1) {
      if (!day.weeks[week].has(pair)) cells.set(pair, [...(cells.get(pair) ?? []), week]);
    }
  }
  return [...cells].map(([pair, weeks]) => ({ pair, weeks })).sort((a, b) => a.pair - b.pair);
}

/** «Рваний день»: у чисельник і знаменник різна кількість пар або різна перша пара (DOMAIN §5). */
export function brokenDayOf(day: DayPresence): { counts: Record<WeekParity, number>; first: Record<WeekParity, number | null> } | null {
  const num = sortedPairs(day.weeks.NUMERATOR);
  const den = sortedPairs(day.weeks.DENOMINATOR);
  if (num.length === 0 && den.length === 0) return null;
  if (num.length === den.length && num[0] === den[0]) return null;
  return {
    counts: { NUMERATOR: num.length, DENOMINATOR: den.length },
    first: { NUMERATOR: num[0] ?? null, DENOMINATOR: den[0] ?? null },
  };
}

export function latePairsOf(day: DayPresence): number {
  const late = new Set<number>();
  for (const week of WEEK_PARITIES) for (const pair of day.weeks[week].keys()) if (pair >= LATE_PAIR_FROM) late.add(pair);
  return late.size;
}

function weekSummary(count: number, first: number | null): string {
  return count === 0 || first === null ? 'пар немає' : `${countLabel(count, PAIR_FORMS)} з ${ordinal(first)}`;
}

function softConflict(kind: TimetableConflict['kind'], message: string, lessons: readonly TemplateLessonDto[], weekday: number, pair: number): TimetableConflict {
  return { kind, severity: 'SOFT', message, lessonIds: lessons.map((l) => l.id), weekday: weekday as Weekday, pair };
}

const groupKey = (lesson: TemplateLessonDto) => lesson.group.id;
const teacherKey = (lesson: TemplateLessonDto) => lesson.teacher?.id ?? null;

// ───────────────────────── Аналіз версії ─────────────────────────

export interface LessonsAnalysis {
  hard: Conflict[];
  soft: TimetableConflict[];
  metrics: MetricsDto;
}

const analysisCache = new WeakMap<readonly TemplateLessonDto[], LessonsAnalysis>();

/** Конфлікти й метрики набору занять. Результат кешується за масивом (зміни створюють новий масив). */
export function analyzeLessons(lessons: readonly TemplateLessonDto[]): LessonsAnalysis {
  const cached = analysisCache.get(lessons);
  if (cached) return cached;

  const soft: TimetableConflict[] = [];
  const perGroup: MetricsDto['perGroup'] = [];
  let groupWindows = 0;
  let brokenDays = 0;
  let latePairs = 0;
  let maxPairsPerDay = 0;

  const groupDays = presenceBy(lessons, groupKey);
  const orderedGroups = GROUPS.filter((group) => groupDays.has(group.id));
  for (const group of orderedGroups) {
    const days = groupDays.get(group.id) ?? new Map<number, DayPresence>();
    const stats = { windows: 0, brokenDays: 0, latePairs: 0, maxPairsPerDay: 0 };
    for (const [weekday, day] of [...days].sort((a, b) => a[0] - b[0])) {
      for (const cell of windowCells(day)) {
        stats.windows += 1;
        soft.push(
          softConflict('WINDOW', `${group.code}: вікно на ${cell.pair} парі — ${weekdayName(weekday)}${weeksText(cell.weeks)}`, day.lessons, weekday, cell.pair),
        );
      }
      const broken = brokenDayOf(day);
      if (broken) {
        stats.brokenDays += 1;
        const firstPair = Math.min(broken.first.NUMERATOR ?? 9, broken.first.DENOMINATOR ?? 9);
        soft.push(
          softConflict(
            'BROKEN_DAY',
            `${group.code}: рваний день — ${weekdayName(weekday)}: у чисельник ${weekSummary(broken.counts.NUMERATOR, broken.first.NUMERATOR)}, у знаменник ${weekSummary(broken.counts.DENOMINATOR, broken.first.DENOMINATOR)}`,
            day.lessons,
            weekday,
            firstPair,
          ),
        );
      }
      stats.latePairs += latePairsOf(day);
      for (const week of WEEK_PARITIES) {
        const pairs = sortedPairs(day.weeks[week]);
        stats.maxPairsPerDay = Math.max(stats.maxPairsPerDay, pairs.length);
        if (pairs.length > MAX_COMFORT_PAIRS) {
          soft.push(
            softConflict('DAY_OVERLOAD', `${group.code}: ${countLabel(pairs.length, PAIR_FORMS)} за день — ${weekdayName(weekday)} (${PARITY_TEXT[week]})`, day.lessons, weekday, pairs[MAX_COMFORT_PAIRS] ?? 1),
          );
        }
        // пари, на яких стоїть кожна дисципліна цього тижня
        const pairsByDiscipline = new Map<Uuid, Set<number>>();
        for (const [pair, list] of day.weeks[week]) {
          for (const lesson of list) pairsByDiscipline.set(lesson.discipline.id, (pairsByDiscipline.get(lesson.discipline.id) ?? new Set()).add(pair));
        }
        for (const [disciplineId, disciplinePairs] of pairsByDiscipline) {
          if (disciplinePairs.size < DISCIPLINE_REPEAT_FROM) continue;
          const repeated = day.lessons.filter((l) => l.discipline.id === disciplineId && runsIn(l.parity, week));
          soft.push(
            softConflict(
              'DISCIPLINE_REPEAT',
              `${group.code}: ${repeated[0]?.discipline.shortName ?? ''} ${disciplinePairs.size} рази за день — ${weekdayName(weekday)} (${PARITY_TEXT[week]})`,
              repeated,
              weekday,
              Math.min(...disciplinePairs),
            ),
          );
        }
      }
    }
    groupWindows += stats.windows;
    brokenDays += stats.brokenDays;
    latePairs += stats.latePairs;
    maxPairsPerDay = Math.max(maxPairsPerDay, stats.maxPairsPerDay);
    perGroup.push({ group: groupRef(group), ...stats });
  }

  let teacherWindows = 0;
  for (const days of presenceBy(lessons, teacherKey).values()) {
    for (const [weekday, day] of [...days].sort((a, b) => a[0] - b[0])) {
      const name = day.lessons[0]?.teacher?.shortName ?? '';
      for (const cell of windowCells(day)) {
        teacherWindows += 1;
        soft.push(
          softConflict('WINDOW', `${name}: вікно на ${cell.pair} парі — ${weekdayName(weekday)}${weeksText(cell.weeks)}`, day.lessons, weekday, cell.pair),
        );
      }
    }
  }

  const analysis: LessonsAnalysis = {
    hard: findHardConflicts(lessons),
    soft,
    metrics: { groupWindows, teacherWindows, brokenDays, latePairs, maxPairsPerDay, perGroup },
  };
  analysisCache.set(lessons, analysis);
  return analysis;
}

const conflictKey = (c: TimetableConflict) => `${c.kind}|${c.message}`;

/** Мʼякі попередження, яких не було до зміни: саме їх показуємо як наслідок дії. */
function newSoftWarnings(before: readonly TemplateLessonDto[], after: readonly TemplateLessonDto[]): TimetableConflict[] {
  const known = new Set(analyzeLessons(before).soft.map(conflictKey));
  return analyzeLessons(after).soft.filter((c) => !known.has(conflictKey(c)));
}

function hardInvolving(lessons: readonly TemplateLessonDto[], ids: ReadonlySet<Uuid>): Conflict[] {
  return analyzeLessons(lessons).hard.filter((c) => c.lessonIds.some((id) => ids.has(id)));
}

// ───────────────────────── Порівняння версій ─────────────────────────

/** Те саме заняття в різних версіях: група, підгрупа, дисципліна, викладач, вид (id у версіях різні). */
const identityOf = (l: TemplateLessonDto) => [l.group.id, l.subgroup, l.discipline.id, l.teacher?.id ?? '-', l.lessonKind].join('|');
const placementOf = (l: TemplateLessonDto) => [l.weekday, l.pair, l.parity, l.room?.id ?? '-'].join('|');

function compareLessons(a: TemplateLessonDto, b: TemplateLessonDto): number {
  return (
    a.group.course - b.group.course ||
    a.group.code.localeCompare(b.group.code, 'uk') ||
    a.weekday - b.weekday ||
    a.pair - b.pair ||
    a.subgroup - b.subgroup
  );
}

export function diffLessons(base: readonly TemplateLessonDto[], target: readonly TemplateLessonDto[]): VersionDiffDto {
  const pool = groupBy(base, identityOf);
  const unmatched: TemplateLessonDto[] = [];
  for (const lesson of target) {
    const candidates = pool.get(identityOf(lesson));
    const index = candidates?.findIndex((c) => placementOf(c) === placementOf(lesson)) ?? -1;
    if (candidates && index >= 0) candidates.splice(index, 1);
    else unmatched.push(lesson);
  }
  const added: TemplateLessonDto[] = [];
  const moved: VersionDiffDto['moved'] = [];
  for (const lesson of unmatched) {
    const from = pool.get(identityOf(lesson))?.shift();
    if (from) moved.push({ from, to: lesson });
    else added.push(lesson);
  }
  const removed = [...pool.values()].flat();
  return {
    added: added.sort(compareLessons),
    removed: removed.sort(compareLessons),
    moved: moved.sort((a, b) => compareLessons(a.to, b.to)),
  };
}

// ───────────────────────── Сховище версій ─────────────────────────

export interface VersionRecord {
  id: Uuid;
  semesterId: Uuid;
  name: string;
  status: TimetableVersionStatus;
  source: TimetableVersionSource;
  effectiveFrom: IsoDate | null;
  publishedAt: string | null;
  createdAt: string;
  lessons: readonly TemplateLessonDto[];
}

/** Копія занять з новими id (у кожній версії свої рядки template_lesson). */
export function copyLessons(lessons: readonly TemplateLessonDto[], versionId: Uuid): TemplateLessonDto[] {
  return lessons.map((lesson) => ({ ...lesson, id: mockId('template-lesson', `${versionId}|${lesson.id}`) }));
}

function findLesson(lessons: readonly TemplateLessonDto[], code: string, weekday: number, pair: number, parity: Parity, discipline: string) {
  const lesson = lessons.find(
    (l) => l.group.code === code && l.weekday === weekday && l.pair === pair && l.parity === parity && l.discipline.shortName === discipline,
  );
  if (!lesson) throw new Error(`Мок-дані неузгоджені: немає заняття ${code} ${discipline} (${weekday}/${pair}/${parity})`);
  return lesson;
}

export const DEMO_DRAFT_ID = mockId('timetable-version', 'demo-draft-4ki1-even-days');

/**
 * ДЕМО-чернетка (у документах її немає): копія опублікованої версії з однією правкою з DOMAIN §5 —
 * половинку ЕУ (чт, 2 пара, чисельник) поставлено в знаменник ср 1 пари поруч із ДК (чисельник).
 * Так обидва «рвані» дні 4КІ1 (середа й четвер) стають рівними.
 */
function demoDraft(published: VersionRecord): VersionRecord {
  const lessons = copyLessons(published.lessons, DEMO_DRAFT_ID);
  const half = findLesson(lessons, '4КІ1', 4, 2, 'NUMERATOR', 'ЕУ');
  return {
    id: DEMO_DRAFT_ID,
    semesterId: ACTIVE_SEMESTER.id,
    name: 'Чернетка: рівні дні 4КІ1',
    status: 'DRAFT',
    source: 'COPY',
    effectiveFrom: null,
    publishedAt: null,
    createdAt: '2026-11-02T08:15:00Z',
    lessons: lessons.map((l) => (l.id === half.id ? { ...l, weekday: 3, pair: 1, parity: 'DENOMINATOR' } : l)),
  };
}

function seedVersions(): VersionRecord[] {
  const published: VersionRecord = {
    id: PUBLISHED_VERSION_ID,
    semesterId: ACTIVE_SEMESTER.id,
    name: 'Розклад I семестру 2026/2027',
    status: 'PUBLISHED',
    source: 'IMPORT',
    effectiveFrom: PUBLISHED_VERSION_EFFECTIVE_FROM,
    // ДЕМО: момент імпорту й публікації синтетичний (у документах його немає)
    publishedAt: '2026-08-31T07:00:00Z',
    createdAt: '2026-08-31T06:40:00Z',
    lessons: TEMPLATE_LESSONS,
  };
  return [published, demoDraft(published)];
}

const versions = createMockStore<VersionRecord[]>(seedVersions);

function versionOr404(id: string): VersionRecord {
  const version = versions.get().find((v) => v.id === id);
  if (!version) throw notFound(`Версію розкладу з id ${id} не знайдено.`);
  return version;
}

function saveVersion(next: VersionRecord): VersionRecord {
  versions.update((list) => list.map((v) => (v.id === next.id ? next : v)));
  return next;
}

function editableOr422(version: VersionRecord): void {
  if (version.status !== 'DRAFT') {
    throw businessRule('VERSION_NOT_EDITABLE', 'Змінювати можна лише чернетку. Створіть копію цієї версії й редагуйте її.');
  }
}

function toVersionDto(version: VersionRecord): TimetableVersionDto {
  const analysis = analyzeLessons(version.lessons);
  return {
    id: version.id,
    name: version.name,
    status: version.status,
    source: version.source,
    effectiveFrom: version.effectiveFrom,
    publishedAt: version.publishedAt,
    lessonCount: version.lessons.length,
    hardConflicts: analysis.hard.length,
    softWarnings: analysis.soft.length,
    metrics: analysis.metrics,
  };
}

const STATUS_ORDER: Record<TimetableVersionStatus, number> = { DRAFT: 0, PUBLISHED: 1, ARCHIVED: 2 };

/** API сховища для генератора (handlers/generation.ts). */
export const timetableMock = {
  find: (id: Uuid): VersionRecord | undefined => versions.get().find((v) => v.id === id),
  published: (semesterId: Uuid): VersionRecord | undefined =>
    versions.get().find((v) => v.semesterId === semesterId && v.status === 'PUBLISHED'),
  addDraft: (input: { semesterId: Uuid; name: string; source: TimetableVersionSource; lessons: readonly TemplateLessonDto[]; id?: Uuid }): VersionRecord => {
    const id = input.id ?? mockId('timetable-version', crypto.randomUUID());
    const record: VersionRecord = {
      id,
      semesterId: input.semesterId,
      name: input.name,
      status: 'DRAFT',
      source: input.source,
      effectiveFrom: null,
      publishedAt: null,
      createdAt: new Date().toISOString(),
      lessons: copyLessons(input.lessons, id),
    };
    versions.update((list) => [...list, record]);
    return record;
  },
};

// ───────────────────────── Валідація запитів ─────────────────────────

const isInt = (value: unknown, min: number, max: number): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;

function validateSlot(body: Partial<MoveLessonRequest>, errors: FieldErrorDto[]): void {
  if (!isInt(body.weekday, 1, 5)) errors.push({ field: 'weekday', message: 'День тижня — від понеділка до пʼятниці' });
  if (!isInt(body.pair, 1, 8)) errors.push({ field: 'pair', message: 'Номер пари — від 1 до 8' });
  if (!PARITY_VALUES.includes(body.parity as Parity)) errors.push({ field: 'parity', message: 'Оберіть: щотижня, чисельник або знаменник' });
}

function resolveRoom(roomId: Uuid | null | undefined, errors: FieldErrorDto[]): RoomDto | null | undefined {
  if (roomId === undefined) return undefined;
  if (roomId === null) return null;
  const room = findRoom(roomId);
  if (!room) errors.push({ field: 'roomId', message: 'Такої аудиторії немає в каталозі' });
  return room;
}

/** Тіло POST/PUT заняття → заняття чернетки або 400 з помилками полів. */
function lessonFromRequest(body: Partial<TemplateLessonRequest>, id: Uuid, base?: TemplateLessonDto): TemplateLessonDto {
  const errors: FieldErrorDto[] = [];
  validateSlot(body, errors);
  const group = typeof body.groupId === 'string' ? findGroup(body.groupId) : undefined;
  if (!group) errors.push({ field: 'groupId', message: 'Оберіть групу' });
  const maxSubgroup = Math.max(2, group?.subgroups?.length ?? 0);
  if (!isInt(body.subgroup, 0, maxSubgroup)) errors.push({ field: 'subgroup', message: 'Такої підгрупи в групі немає' });
  const discipline = typeof body.disciplineId === 'string' ? findDiscipline(body.disciplineId) : undefined;
  if (!discipline) errors.push({ field: 'disciplineId', message: 'Оберіть дисципліну' });
  let teacher: TemplateLessonDto['teacher'] = null;
  if (body.teacherId != null) {
    const found = findTeacher(body.teacherId);
    if (found) teacher = teacherRef(found);
    else errors.push({ field: 'teacherId', message: 'Такого викладача немає в каталозі' });
  }
  const room = resolveRoom(body.roomId ?? null, errors);
  if (!LESSON_KINDS.includes(body.lessonKind as LessonKind)) errors.push({ field: 'lessonKind', message: 'Оберіть вид заняття' });
  if (errors.length > 0 || !group || !discipline) throw badRequest('Перевірте поля заняття.', errors);
  return {
    id,
    weekday: body.weekday as Weekday,
    pair: body.pair as number,
    parity: body.parity as Parity,
    group: groupRef(group),
    subgroup: body.subgroup as number,
    discipline: disciplineRef(discipline),
    teacher,
    room: room ? roomRef(room) : null,
    lessonKind: body.lessonKind as LessonKind,
    loadItemId: body.loadItemId ?? base?.loadItemId ?? null,
    pinned: body.pinned ?? base?.pinned ?? false,
    online: body.online ?? base?.online ?? false,
    streamId: body.streamId ?? base?.streamId ?? null,
  };
}

/** Відповідь на правку: жорсткі конфлікти із заняттям і нові мʼякі попередження. */
function mutationResult(before: readonly TemplateLessonDto[], after: readonly TemplateLessonDto[], lesson: TemplateLessonDto): LessonMutationResult {
  const hard = hardInvolving(after, new Set([lesson.id]));
  return { lesson, conflicts: [...hard, ...newSoftWarnings(before, after)] };
}

// ───────────────────────── Публікація (DOMAIN §6) ─────────────────────────

/** Скільки занять на дати зʼявиться: навчальні дні [від, кінець семестру], парність тижня, без практики груп. */
function materializedCount(lessons: readonly TemplateLessonDto[], from: IsoDate, until: IsoDate): number {
  let count = 0;
  for (let date = from; compareIsoDates(date, until) <= 0; date = addDays(date, 1)) {
    if (!isStudyDay(date)) continue;
    const weekday = isoWeekday(date);
    const parity = parityOn(date);
    for (const lesson of lessons) {
      if (lesson.weekday !== weekday || !runsIn(lesson.parity, parity)) continue;
      const group = groupOf(lesson);
      if (periodsOn(date, group).some((p) => p.kind === 'PRACTICE')) continue;
      count += 1;
    }
  }
  return count;
}

function studyDates(from: IsoDate, until: IsoDate): IsoDate[] {
  const dates: IsoDate[] = [];
  for (let date = from; compareIsoDates(date, until) <= 0; date = addDays(date, 1)) if (isStudyDay(date)) dates.push(date);
  return dates;
}

// ───────────────────────── Обробники ─────────────────────────

const base = '/admin/timetable/versions';

export const handlers = [
  http.get(apiPath(base), ({ request }) => {
    const semesterId = new URL(request.url).searchParams.get('semesterId');
    const list = versions
      .get()
      .filter((v) => !semesterId || v.semesterId === semesterId)
      .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || b.createdAt.localeCompare(a.createdAt));
    return HttpResponse.json(list.map(toVersionDto));
  }),

  http.post(apiPath(base), async ({ request }) => {
    const body = await readJson<Partial<CreateVersionRequest>>(request);
    const errors: FieldErrorDto[] = [];
    const name = typeof body.name === 'string' ? body.name.trim() : '';
    if (name.length === 0) errors.push({ field: 'name', message: 'Вкажіть назву версії' });
    else if (name.length > 120) errors.push({ field: 'name', message: 'Назва — до 120 символів' });
    const semester = SEMESTERS.find((s) => s.id === body.semesterId);
    if (!semester) errors.push({ field: 'semesterId', message: 'Оберіть семестр' });
    const source = body.copyFromVersionId ? versions.get().find((v) => v.id === body.copyFromVersionId) : undefined;
    if (body.copyFromVersionId && !source) errors.push({ field: 'copyFromVersionId', message: 'Версії, яку копіюєте, немає' });
    if (errors.length > 0 || !semester) return badRequest('Перевірте поля версії.', errors);
    if (versions.get().some((v) => v.semesterId === semester.id && v.name.toLocaleLowerCase('uk') === name.toLocaleLowerCase('uk'))) {
      return badRequest('Версія з такою назвою вже є.', [{ field: 'name', message: 'Така назва вже є в цьому семестрі' }]);
    }
    const record = timetableMock.addDraft({
      semesterId: semester.id,
      name,
      source: source ? 'COPY' : 'MANUAL',
      lessons: source?.lessons ?? [],
    });
    return HttpResponse.json(toVersionDto(record), { status: 201, headers: { Location: apiPath(`${base}/${record.id}`) } });
  }),

  http.get(apiPath(`${base}/:id/lessons`), ({ params, request }) => {
    const version = versionOr404(String(params.id));
    const url = new URL(request.url);
    const groupId = url.searchParams.get('groupId');
    const teacherId = url.searchParams.get('teacherId');
    const roomId = url.searchParams.get('roomId');
    const lessons = version.lessons.filter(
      (l) => (!groupId || l.group.id === groupId) && (!teacherId || l.teacher?.id === teacherId) && (!roomId || l.room?.id === roomId),
    );
    return HttpResponse.json(lessons);
  }),

  http.post(apiPath(`${base}/:id/lessons`), async ({ params, request }) => {
    const version = versionOr404(String(params.id));
    editableOr422(version);
    const lesson = lessonFromRequest(await readJson<Partial<TemplateLessonRequest>>(request), mockId('template-lesson', crypto.randomUUID()));
    const next = saveVersion({ ...version, lessons: [...version.lessons, lesson] });
    return HttpResponse.json(mutationResult(version.lessons, next.lessons, lesson), {
      status: 201,
      headers: { Location: apiPath(`${base}/${version.id}/lessons/${lesson.id}`) },
    });
  }),

  http.put(apiPath(`${base}/:id/lessons/:lessonId`), async ({ params, request }) => {
    const version = versionOr404(String(params.id));
    editableOr422(version);
    const current = version.lessons.find((l) => l.id === params.lessonId);
    if (!current) return notFound(`Заняття з id ${String(params.lessonId)} у цій версії немає.`);
    const lesson = lessonFromRequest(await readJson<Partial<TemplateLessonRequest>>(request), current.id, current);
    const next = saveVersion({ ...version, lessons: version.lessons.map((l) => (l.id === current.id ? lesson : l)) });
    return HttpResponse.json(mutationResult(version.lessons, next.lessons, lesson));
  }),

  http.delete(apiPath(`${base}/:id/lessons/:lessonId`), ({ params }) => {
    const version = versionOr404(String(params.id));
    editableOr422(version);
    if (!version.lessons.some((l) => l.id === params.lessonId)) {
      return notFound(`Заняття з id ${String(params.lessonId)} у цій версії немає.`);
    }
    saveVersion({ ...version, lessons: version.lessons.filter((l) => l.id !== params.lessonId) });
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(apiPath(`${base}/:id/lessons/:lessonId/move`), async ({ params, request }) => {
    const version = versionOr404(String(params.id));
    editableOr422(version);
    const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
    const lesson = version.lessons.find((l) => l.id === params.lessonId);
    if (!lesson) return notFound(`Заняття з id ${String(params.lessonId)} у цій версії немає.`);
    const body = await readJson<Partial<MoveLessonRequest>>(request);
    const errors: FieldErrorDto[] = [];
    validateSlot(body, errors);
    const room = resolveRoom(body.roomId, errors);
    if (errors.length > 0) return badRequest('Перевірте новий слот.', errors);

    // потік (одна пара для кількох груп) переїжджає разом
    const mates = lesson.streamId ? version.lessons.filter((l) => l.streamId === lesson.streamId) : [lesson];
    const moved = new Map(
      mates.map((l) => [
        l.id,
        {
          ...l,
          weekday: body.weekday as Weekday,
          pair: body.pair as number,
          parity: body.parity as Parity,
          ...(room ? { room: roomRef(room) } : {}),
        },
      ]),
    );
    const nextLessons = version.lessons.map((l) => moved.get(l.id) ?? l);
    const hard = hardInvolving(nextLessons, new Set(moved.keys()));
    if (hard.length > 0) return conflict('Пару не можна поставити в цей слот: зʼявиться конфлікт.', hard);
    if (!dryRun) saveVersion({ ...version, lessons: nextLessons });
    const result: LessonMutationResult = {
      lesson: moved.get(lesson.id) as TemplateLessonDto,
      conflicts: newSoftWarnings(version.lessons, nextLessons),
    };
    return HttpResponse.json(result);
  }),

  http.get(apiPath(`${base}/:id/conflicts`), ({ params }) => {
    const { hard, soft } = analyzeLessons(versionOr404(String(params.id)).lessons);
    const body: TimetableConflictsDto = { hard, soft };
    return HttpResponse.json(body);
  }),

  http.get(apiPath(`${base}/:id/metrics`), ({ params }) =>
    HttpResponse.json(analyzeLessons(versionOr404(String(params.id)).lessons).metrics),
  ),

  http.get(apiPath(`${base}/:a/diff/:b`), ({ params }) => {
    const a = versionOr404(String(params.a));
    const b = versionOr404(String(params.b));
    return HttpResponse.json(diffLessons(a.lessons, b.lessons));
  }),

  http.post(apiPath(`${base}/:id/publish`), async ({ params, request }) => {
    const version = versionOr404(String(params.id));
    const body = await readJson<Partial<PublishVersionRequest>>(request);
    const semester = SEMESTERS.find((s) => s.id === version.semesterId) ?? ACTIVE_SEMESTER;
    const effectiveFrom = body.effectiveFrom;
    if (!isIsoDate(effectiveFrom)) {
      return badRequest('Вкажіть дату, з якої діє розклад.', [{ field: 'effectiveFrom', message: 'Дата у форматі РРРР-ММ-ДД' }]);
    }
    if (compareIsoDates(effectiveFrom, semester.startsOn) < 0 || compareIsoDates(effectiveFrom, semester.endsOn) > 0) {
      return badRequest('Дата поза семестром.', [
        {
          field: 'effectiveFrom',
          message: `Дата має бути в межах семестру: ${formatNumericDate(semester.startsOn)}–${formatNumericDate(semester.endsOn)}`,
        },
      ]);
    }
    if (compareIsoDates(effectiveFrom, todayInKyiv()) < 0) {
      return badRequest('Дата вже минула.', [{ field: 'effectiveFrom', message: 'Розклад не можна ввести заднім числом' }]);
    }
    if (version.status !== 'DRAFT') return businessRule('VERSION_NOT_DRAFT', 'Опублікувати можна лише чернетку.');
    const { hard } = analyzeLessons(version.lessons);
    if (hard.length > 0) return conflict('Версію не опубліковано: є жорсткі конфлікти. Виправте їх і спробуйте ще раз.', hard);

    const publishedAt = new Date().toISOString();
    versions.update((list) =>
      list.map((v) => {
        if (v.id === version.id) return { ...v, status: 'PUBLISHED', effectiveFrom, publishedAt };
        if (v.semesterId === version.semesterId && v.status === 'PUBLISHED') return { ...v, status: 'ARCHIVED' };
        return v;
      }),
    );
    const response: PublishVersionResponse = { materializedLessons: materializedCount(version.lessons, effectiveFrom, semester.endsOn) };
    emitServerEvent('schedule.changed', {
      dates: studyDates(effectiveFrom, semester.endsOn),
      groupIds: [...new Set(version.lessons.map((l) => l.group.id))],
      teacherIds: [...new Set(version.lessons.flatMap((l) => (l.teacher ? [l.teacher.id] : [])))],
      reason: 'VERSION_PUBLISHED',
    });
    return HttpResponse.json(response);
  }),

  // ── Довідники, потрібні редактору. Запасні: файли інших областей ідуть раніше за абеткою,
  //    тож якщо вони змокують ті самі шляхи, відповідатимуть саме вони.
  http.get(apiPath('/public/groups'), () => HttpResponse.json(GROUPS.map(toPublicGroup))),
  http.get(apiPath('/public/teachers'), () => HttpResponse.json(TEACHERS.map(toPublicTeacher))),
  http.get(apiPath('/public/rooms'), () => HttpResponse.json(ROOMS.map(toPublicRoom))),
  http.get(apiPath('/admin/shift-windows'), () => HttpResponse.json(SHIFT_WINDOWS)),
  http.get(apiPath('/admin/disciplines'), ({ request }) => {
    const url = new URL(request.url);
    const q = url.searchParams.get('q')?.trim() ?? '';
    const list: DisciplineDto[] = q ? DISCIPLINES.filter((d) => matchScore(q, d.shortName, d.name) >= 40) : [...DISCIPLINES];
    return HttpResponse.json(paginate(list, url));
  }),
];
