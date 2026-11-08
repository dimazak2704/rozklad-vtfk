/**
 * Правила DOMAIN §7 для мок-API: зачеплені пари, зайнятість викладачів і груп з урахуванням рішень,
 * варіанти для пари з оцінкою й причинами. Чисті функції над станом (World) — їх використовують і
 * обробники, і демо-сід, і тести.
 */
import type { AbsenceDto, IsoDate, LessonView, OptionDto, TeacherDto, Uuid, Weekday } from '@/api/types';
import { addDays, compareIsoDates, formatShortWeekdayDate, isoWeekday } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import {
  disciplinesOfTeacher,
  findGroup,
  findTeacher,
  SHIFT_WINDOWS,
  TEACHERS,
  TEMPLATE_LESSONS,
  teacherRef,
} from '../../fixtures';
import { studyDaysBetween, templateLessons } from './lessons';
import { LIVE_STATUSES, type ChangeRecord, type World } from './world';

/** Скільки кандидатів-викладачів повертати (DOMAIN §7: «Top 10»). */
export const TOP_TEACHERS = 10;
const TOP_DISCIPLINES = 5;
const ADDITIONAL_SLOTS = 3;
const ADDITIONAL_WINDOW_DAYS = 14;
/** Більше пар на день — мʼяке попередження (DOMAIN §5: > 4 пар). */
const MAX_COMFORT_PAIRS = 4;

const PAIR_FORMS = ['пару', 'пари', 'пар'] as const;
const SUBSTITUTION_FORMS = ['заміна', 'заміни', 'замін'] as const;

// ───────────────────────── Рішення щодо пари ─────────────────────────

function byCreation(a: ChangeRecord, b: ChangeRecord): number {
  return b.createdAt.localeCompare(a.createdAt);
}

/** Остання зміна, створена для заняття (будь-якого стану). */
export function latestChangeFor(world: World, lessonId: Uuid): ChangeRecord | null {
  let latest: ChangeRecord | null = null;
  for (const change of world.changes.values()) {
    if (change.originalLessonId !== lessonId) continue;
    if (!latest || byCreation(change, latest) < 0) latest = change;
  }
  return latest;
}

/** Рішення щодо пари: остання зміна, якщо її не скасували (відхилена теж показується). */
export function decisionFor(world: World, lessonId: Uuid): ChangeRecord | null {
  const latest = latestChangeFor(world, lessonId);
  return latest && latest.status !== 'CANCELLED' ? latest : null;
}

/** Пару закрито живою зміною (відхилена чи скасована пару не закривають). */
export function isCovered(decision: ChangeRecord | null): boolean {
  return decision !== null && LIVE_STATUSES.has(decision.status);
}

/** Пари, які займає зміна: блок — проміжок, перестановка — лише нова пара. */
export function changePairs(change: Pick<ChangeRecord, 'kind' | 'pairFrom' | 'pairTo'>): number[] {
  if (change.kind === 'SWAP' || change.pairTo === null || change.pairTo === change.pairFrom) return [change.pairFrom];
  const pairs: number[] = [];
  for (let pair = change.pairFrom; pair <= change.pairTo; pair += 1) pairs.push(pair);
  return pairs;
}

/** Чи переставлено заняття на іншу пару живою перестановкою. */
function swappedAway(world: World, lessonId: Uuid): boolean {
  for (const change of world.changes.values()) {
    if (change.kind === 'SWAP' && change.swapLessonId === lessonId && LIVE_STATUSES.has(change.status)) return true;
  }
  return false;
}

/** Викладач більше не веде це заняття: його скасували, віддали іншому або переставили. */
function lessonLeftTeacher(world: World, lesson: LessonView): boolean {
  if (swappedAway(world, lesson.id)) return true;
  const decision = decisionFor(world, lesson.id);
  if (!decision || !isCovered(decision)) return false;
  return decision.kind === 'CANCEL' || decision.kind === 'SWAP' || decision.new.teacher?.id !== lesson.teacher?.id;
}

// ───────────────────────── Відсутності й зайнятість ─────────────────────────

export function absenceCovers(absence: AbsenceDto, date: IsoDate, pair?: number): boolean {
  if (absence.status !== 'ACTIVE') return false;
  if (compareIsoDates(date, absence.dateFrom) < 0 || compareIsoDates(date, absence.dateTo) > 0) return false;
  return pair === undefined || absence.pairs === null || absence.pairs.includes(pair);
}

export function isAbsent(world: World, teacherId: Uuid, date: IsoDate, pair?: number): boolean {
  for (const absence of world.absences.values()) {
    if (absence.teacher.id === teacherId && absenceCovers(absence, date, pair)) return true;
  }
  return false;
}

/** Що викладач робить на парі: для повідомлень про конфлікт. */
export interface TeacherSlot {
  pair: number;
  label: string;
  lessonId: Uuid | null;
}

/** Пари викладача на дату з урахуванням рішень: власні заняття, яких не забрали, плюс призначені зміни. */
export function teacherSlots(world: World, teacherId: Uuid, date: IsoDate): Map<number, TeacherSlot> {
  const slots = new Map<number, TeacherSlot>();
  for (const lesson of templateLessons(date)) {
    if (lesson.teacher?.id !== teacherId || lessonLeftTeacher(world, lesson)) continue;
    slots.set(lesson.pair, { pair: lesson.pair, label: `${lesson.discipline.shortName} у ${lesson.group.code}`, lessonId: lesson.id });
  }
  for (const change of world.changes.values()) {
    if (change.date !== date || !LIVE_STATUSES.has(change.status) || change.kind === 'CANCEL') continue;
    if (change.new.teacher?.id !== teacherId) continue;
    const discipline = change.new.discipline?.shortName ?? change.original.discipline?.shortName ?? 'заняття';
    for (const pair of changePairs(change)) {
      slots.set(pair, { pair, label: `${discipline} у ${change.group.code} (заміна)`, lessonId: null });
    }
  }
  return slots;
}

export function isTeacherFree(world: World, teacherId: Uuid, date: IsoDate, pair: number): boolean {
  return !teacherSlots(world, teacherId, date).has(pair) && !isAbsent(world, teacherId, date, pair);
}

/** Пари групи на дату з урахуванням рішень (скасовані й переставлені прибрано, додаткові додано). */
export function groupPairs(world: World, groupId: Uuid, date: IsoDate): number[] {
  const pairs = new Set<number>();
  for (const lesson of templateLessons(date)) {
    if (lesson.group.id !== groupId || swappedAway(world, lesson.id)) continue;
    const decision = decisionFor(world, lesson.id);
    if (decision && isCovered(decision) && (decision.kind === 'CANCEL' || decision.kind === 'SWAP')) {
      if (decision.kind === 'SWAP') pairs.add(decision.pairFrom);
      continue;
    }
    pairs.add(lesson.pair);
  }
  for (const change of world.changes.values()) {
    if (change.date === date && change.group.id === groupId && change.kind === 'ADDITIONAL' && LIVE_STATUSES.has(change.status)) {
      pairs.add(change.pairFrom);
    }
  }
  return [...pairs].sort((a, b) => a - b);
}

/** Заміни викладача за календарний місяць дати (штраф −8 за кожну, DOMAIN §7). */
export function substitutionsInMonth(world: World, teacherId: Uuid, date: IsoDate): number {
  const month = date.slice(0, 7);
  let count = 0;
  for (const change of world.changes.values()) {
    if (change.new.teacher?.id !== teacherId || change.kind === 'CANCEL') continue;
    if (change.date.slice(0, 7) !== month) continue;
    if (change.status === 'PENDING_CONFIRMATION' || change.status === 'CONFIRMED' || change.status === 'APPLIED') count += 1;
  }
  return count;
}

// ───────────────────────── Зачеплені пари ─────────────────────────

export interface AffectedItem {
  date: IsoDate;
  lesson: LessonView;
  decision: ChangeRecord | null;
}

/** Заплановані пари відсутнього викладача в межах відсутності (DOMAIN §7) з поточними рішеннями. */
export function affectedLessons(world: World, absence: AbsenceDto): AffectedItem[] {
  if (absence.status !== 'ACTIVE') return [];
  const items: AffectedItem[] = [];
  for (const date of studyDaysBetween(absence.dateFrom, absence.dateTo)) {
    for (const lesson of templateLessons(date)) {
      if (lesson.teacher?.id !== absence.teacher.id) continue;
      if (absence.pairs !== null && !absence.pairs.includes(lesson.pair)) continue;
      items.push({ date, lesson, decision: decisionFor(world, lesson.id) });
    }
  }
  return items.sort((a, b) => a.date.localeCompare(b.date) || a.lesson.pair - b.lesson.pair || a.lesson.subgroup - b.lesson.subgroup);
}

// ───────────────────────── Варіанти ─────────────────────────

/** Варіант — рівно OptionDto core: усі поля є завжди (null, коли не стосуються), `date`/`pair` — лише у відпрацювання. */
export type MockOption = OptionDto;

interface DayFit {
  score: number;
  reasons: string[];
  warnings: string[];
}

/** Як пара вписується в день викладача: вікно, вже має пари, перевантаження, заміни цього місяця. */
function dayFit(world: World, teacherId: Uuid, date: IsoDate, pair: number): DayFit {
  const pairs = [...teacherSlots(world, teacherId, date).keys()].sort((a, b) => a - b);
  const reasons: string[] = [];
  const warnings: string[] = [];
  let score = 0;
  const before = pairs.filter((p) => p < pair).at(-1);
  const after = pairs.find((p) => p > pair);
  if (before !== undefined && after !== undefined) {
    score += 15;
    reasons.push(`Закриває вікно між ${before} і ${after} парою`);
  }
  if (pairs.length > 0) {
    score += 10;
    reasons.push(`Вже має ${countLabel(pairs.length, PAIR_FORMS)} цього дня`);
  }
  if (pairs.length + 1 > MAX_COMFORT_PAIRS) warnings.push(`Буде ${pairs.length + 1} пар цього дня`);
  const subs = substitutionsInMonth(world, teacherId, date);
  if (subs > 0) {
    score -= Math.min(24, subs * 8);
    warnings.push(`${countLabel(subs, SUBSTITUTION_FORMS)} цього місяця`);
  }
  return { score, reasons, warnings };
}

const clampScore = (value: number) => Math.max(0, Math.min(100, Math.round(value)));

function sameCommission(a: TeacherDto | undefined, b: TeacherDto | undefined): boolean {
  return Boolean(a?.cycleCommission && b?.cycleCommission && a.cycleCommission.id === b.cycleCommission.id);
}

/** 1. Заміна викладача: вільні й присутні колеги, ранжовані за DOMAIN §7. */
function replaceTeacherOptions(world: World, date: IsoDate, lesson: LessonView): MockOption[] {
  const absentTeacher = lesson.teacher ? findTeacher(lesson.teacher.id) : undefined;
  const ranked = TEACHERS.filter((t) => t.active && t.id !== lesson.teacher?.id && isTeacherFree(world, t.id, date, lesson.pair))
    .map((teacher) => {
      const reasons: string[] = [];
      let score = 0;
      if (disciplinesOfTeacher(teacher.id).has(lesson.discipline.id)) {
        score += 50;
        reasons.push('Веде цю дисципліну');
      }
      if (sameCommission(teacher, absentTeacher)) {
        score += 20;
        reasons.push('Та сама циклова комісія');
      }
      const fit = dayFit(world, teacher.id, date, lesson.pair);
      return {
        teacher,
        subs: substitutionsInMonth(world, teacher.id, date),
        option: {
          kind: 'REPLACE_TEACHER' as const,
          score: clampScore(score + fit.score),
          title: teacher.shortName,
          reasons: [...reasons, ...fit.reasons],
          teacher: teacherRef(teacher),
          discipline: lesson.discipline,
          room: lesson.room ?? null,
          swapLesson: null,
          warnings: fit.warnings,
          date: null,
          pair: null,
        },
      };
    })
    .sort(
      (a, b) =>
        b.option.score - a.option.score || a.subs - b.subs || a.teacher.shortName.localeCompare(b.teacher.shortName, 'uk'),
    );
  return ranked.slice(0, TOP_TEACHERS).map((entry) => entry.option);
}

/** 2. Інша дисципліна групи, викладач якої вільний (DOMAIN §7). Відставання від плану тут невідоме. */
function replaceDisciplineOptions(world: World, date: IsoDate, lesson: LessonView): MockOption[] {
  const seen = new Set<string>();
  const options: MockOption[] = [];
  for (const template of TEMPLATE_LESSONS) {
    if (template.group.id !== lesson.group.id || template.subgroup !== lesson.subgroup) continue;
    if (template.discipline.id === lesson.discipline.id || !template.teacher) continue;
    if (template.teacher.id === lesson.teacher?.id) continue;
    const key = `${template.discipline.id}|${template.teacher.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    if (!isTeacherFree(world, template.teacher.id, date, lesson.pair)) continue;
    const fit = dayFit(world, template.teacher.id, date, lesson.pair);
    options.push({
      kind: 'REPLACE_DISCIPLINE',
      score: clampScore(40 + fit.score),
      title: `${template.discipline.shortName} — ${template.teacher.shortName}`,
      reasons: ['Дисципліна групи за розкладом', ...fit.reasons],
      teacher: template.teacher,
      discipline: template.discipline,
      room: lesson.room ?? null,
      swapLesson: null,
      warnings: fit.warnings,
      date: null,
      pair: null,
    });
  }
  return options.sort((a, b) => b.score - a.score || a.title.localeCompare(b.title, 'uk')).slice(0, TOP_DISCIPLINES);
}

/** 3. Перестановка: пізніша пара групи того ж дня, чий викладач вільний на цій парі. */
function swapOptions(world: World, date: IsoDate, lesson: LessonView): MockOption[] {
  const groupDay = groupPairs(world, lesson.group.id, date);
  const lastPair = groupDay.at(-1);
  const sameScope = templateLessons(date).filter((l) => l.group.id === lesson.group.id && l.pair > lesson.pair);
  const options: MockOption[] = [];
  for (const later of sameScope) {
    if (later.subgroup !== lesson.subgroup || !later.teacher || later.teacher.id === lesson.teacher?.id) continue;
    // на тій парі в групи має бути саме одне заняття того ж складу, інакше переставляти довелося б кілька
    if (sameScope.filter((l) => l.pair === later.pair).length !== 1) continue;
    if (decisionFor(world, later.id) || swappedAway(world, later.id)) continue;
    if (!isTeacherFree(world, later.teacher.id, date, lesson.pair)) continue;
    const isLast = later.pair === lastPair;
    options.push({
      kind: 'SWAP',
      score: isLast ? 60 : 35,
      title: `Переставити ${later.pair} пару на ${lesson.pair}`,
      reasons: [
        `${later.discipline.shortName} — ${later.teacher.shortName}: вільний на ${lesson.pair} парі`,
        ...(isLast ? ['Група піде раніше'] : []),
      ],
      teacher: later.teacher,
      discipline: later.discipline,
      room: later.room ?? null,
      swapLesson: later,
      warnings: isLast ? [] : [`Утвориться вікно на ${later.pair} парі`],
      date: null,
      pair: null,
    });
  }
  return options.sort((a, b) => b.score - a.score);
}

/** 4. Скасування: краще, коли пара перша чи остання в групи цього дня. */
function cancelOption(world: World, date: IsoDate, lesson: LessonView): MockOption {
  const others = groupPairs(world, lesson.group.id, date).filter((p) => p !== lesson.pair);
  const reasons: string[] = [];
  const warnings: string[] = [];
  let score: number;
  if (others.length === 0) {
    score = 50;
    reasons.push('Інших пар цього дня в групи немає');
  } else if (lesson.pair < (others[0] ?? 0)) {
    score = 55;
    reasons.push('Перша пара: група прийде пізніше');
  } else if (lesson.pair > (others.at(-1) ?? 0)) {
    score = 55;
    reasons.push('Остання пара: група піде раніше');
  } else {
    score = 20;
    warnings.push('Утвориться вікно в розкладі групи');
  }
  if (lesson.subgroup > 0) reasons.push(`Лише для ${lesson.subgroup} підгрупи`);
  return {
    kind: 'CANCEL',
    score,
    title: 'Пари не буде',
    reasons,
    teacher: null,
    discipline: lesson.discipline,
    room: null,
    swapLesson: null,
    warnings,
    date: null,
    pair: null,
  };
}

function shiftWindow(groupId: Uuid, weekday: Weekday) {
  const group = findGroup(groupId);
  return SHIFT_WINDOWS.find((w) => w.shift === (group?.shift ?? 1) && w.weekday === weekday);
}

/** 5. Відпрацювання: вільний слот групи й викладача протягом 14 днів, поруч з іншими парами групи. */
function additionalOptions(world: World, date: IsoDate, lesson: LessonView): MockOption[] {
  const teacherId = lesson.teacher?.id;
  if (!teacherId) return [];
  const options: MockOption[] = [];
  for (const day of studyDaysBetween(addDays(date, 1), addDays(date, ADDITIONAL_WINDOW_DAYS))) {
    const window = shiftWindow(lesson.group.id, isoWeekday(day) as Weekday);
    const pairs = groupPairs(world, lesson.group.id, day);
    const first = pairs[0];
    const last = pairs.at(-1);
    if (!window || first === undefined || last === undefined) continue;
    const candidates: Array<{ pair: number; reason: string }> = [];
    if (last + 1 <= window.lastPair) candidates.push({ pair: last + 1, reason: `Одразу після ${last} пари групи` });
    if (first - 1 >= window.firstPair) candidates.push({ pair: first - 1, reason: `Перед ${first} парою групи` });
    const slot = candidates.find((c) => isTeacherFree(world, teacherId, day, c.pair));
    if (!slot) continue;
    const teacherBusy = teacherSlots(world, teacherId, day).size > 0;
    options.push({
      kind: 'ADDITIONAL',
      score: teacherBusy ? 35 : 30,
      title: `Відпрацювання: ${formatShortWeekdayDate(day)}, ${slot.pair} пара`,
      reasons: ['Група й викладач вільні', slot.reason, ...(teacherBusy ? ['Викладач і так буде в коледжі'] : [])],
      teacher: lesson.teacher ?? null,
      discipline: lesson.discipline,
      room: lesson.room ?? null,
      swapLesson: null,
      warnings: [],
      date: day,
      pair: slot.pair,
    });
    if (options.length >= ADDITIONAL_SLOTS) break;
  }
  return options;
}

const KIND_ORDER: Record<OptionDto['kind'], number> = {
  REPLACE_TEACHER: 0,
  REPLACE_DISCIPLINE: 1,
  SWAP: 2,
  CANCEL: 3,
  ADDITIONAL: 4,
  ROOM_CHANGE: 5,
  BLOCK: 6,
  DISCIPLINE_FINISHED: 7,
};

/** Усі варіанти для пари, від найкращого: оцінка, далі звичний порядок видів. */
export function optionsFor(world: World, date: IsoDate, lesson: LessonView): MockOption[] {
  const options = [
    ...replaceTeacherOptions(world, date, lesson),
    ...replaceDisciplineOptions(world, date, lesson),
    ...swapOptions(world, date, lesson),
    cancelOption(world, date, lesson),
    ...additionalOptions(world, date, lesson),
  ];
  return options.sort((a, b) => b.score - a.score || KIND_ORDER[a.kind] - KIND_ORDER[b.kind]);
}
