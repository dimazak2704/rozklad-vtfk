/**
 * Правила DOMAIN §7 для мок-API: зачеплені пари, зайнятість викладачів і груп з урахуванням рішень,
 * варіанти для пари у формі рекомендатора v2 (без балів). Чисті функції над станом (World) — їх
 * використовують і обробники, і демо-сід, і тести.
 */
import type { AbsenceDto, ChangeKind, DeclinedDto, IsoDate, LessonView, OptionBand, OptionDto, Uuid } from '@/api/types';
import { compareIsoDates, todayInKyiv } from '@/lib/date';
import { TEMPLATE_LESSONS } from '../../fixtures';
import { studyDaysBetween, templateLessons } from './lessons';
import { LIVE_STATUSES, type ChangeRecord, type World } from './world';

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

// ───────────────────────── Варіанти (рекомендатор v2, DOMAIN §7) ─────────────────────────
//
// Мок не відтворює вартості core (навантаження, відставання від плану, справедливість): лише форму відповіді
// й зрозумілий порядок — смуга, вид, назва. Чипи — у формулюваннях core (OptionRanker), без балів.

/** Варіант — рівно OptionDto core: усі поля є завжди (null, коли не стосуються). */
export type MockOption = OptionDto;

export interface MockLessonOptions {
  options: MockOption[];
  declined: DeclinedDto[];
  past: boolean;
}

const BAND_ORDER: Record<OptionBand, number> = { CLEAN: 0, EASY_CANCEL: 1, CAUTION: 2, LAST: 3 };
const KIND_ORDER: Partial<Record<ChangeKind, number>> = { REPLACE_DISCIPLINE: 0, MERGE_SUBGROUPS: 1, SWAP: 2, CANCEL: 3 };
const UK = new Intl.Collator('uk');

type Draft = Omit<MockOption, 'score' | 'recommended'>;

function draft(input: Partial<Draft> & Pick<Draft, 'kind' | 'title' | 'band'>): Draft {
  return {
    reasons: [],
    warnings: [],
    teacher: null,
    discipline: null,
    room: null,
    swapLesson: null,
    date: null,
    pair: null,
    suggestedRoom: null,
    ...input,
  };
}

/** Хто відмовився від запиту саме на цю пару (з історії змін): core показує їх і не пропонує знову. */
export function declinedFor(world: World, lessonId: Uuid): DeclinedDto[] {
  const declined: DeclinedDto[] = [];
  for (const change of world.changes.values()) {
    if (change.originalLessonId !== lessonId || !change.new.teacher) continue;
    const entry = change.history.find((h) => h.to === 'DECLINED');
    if (entry) declined.push({ teacher: change.new.teacher, at: entry.at });
  }
  return declined.sort((a, b) => a.at.localeCompare(b.at));
}

/** «Сьогодні пар немає» / «Сьогодні має 2, 4 пари»; закрите вікно — окремою причиною. */
function dayShape(world: World, teacherId: Uuid, date: IsoDate, pair: number): string[] {
  const pairs = [...teacherSlots(world, teacherId, date).keys()].sort((a, b) => a - b);
  if (pairs.length === 0) return ['Сьогодні пар немає'];
  const before = pairs.filter((p) => p < pair).at(-1);
  const after = pairs.find((p) => p > pair);
  const reasons = before !== undefined && after !== undefined ? ['Закриває вікно в розкладі викладача'] : [];
  reasons.push(`Сьогодні має ${pairs.join(', ')} ${pairs.length === 1 ? 'пару' : 'пари'}`);
  return reasons;
}

/** Аудиторія вільна на парі: у шаблоні дня її не займає інше заняття. */
function roomFree(roomId: Uuid, date: IsoDate, pair: number, except: Uuid): boolean {
  return !templateLessons(date).some((l) => l.pair === pair && l.room?.id === roomId && l.id !== except);
}

/** 1. Інша дисципліна тієї самої групи й складу: її викладач вільний — один варіант на викладача. */
function poolOptions(world: World, date: IsoDate, lesson: LessonView, declined: ReadonlySet<Uuid>): Draft[] {
  const byTeacher = new Map<Uuid, Array<(typeof TEMPLATE_LESSONS)[number]>>();
  for (const template of TEMPLATE_LESSONS) {
    if (template.group.id !== lesson.group.id || template.subgroup !== lesson.subgroup || !template.teacher) continue;
    if (template.discipline.id === lesson.discipline.id || template.teacher.id === lesson.teacher?.id) continue;
    const list = byTeacher.get(template.teacher.id) ?? [];
    if (!list.some((l) => l.discipline.id === template.discipline.id)) list.push(template);
    byTeacher.set(template.teacher.id, list);
  }
  const options: Draft[] = [];
  for (const [teacherId, items] of byTeacher) {
    if (declined.has(teacherId) || !isTeacherFree(world, teacherId, date, lesson.pair)) continue;
    const [first, ...others] = [...items].sort((a, b) => UK.compare(a.discipline.shortName, b.discipline.shortName));
    if (!first?.teacher) continue;
    const withGroup = [...teacherSlots(world, teacherId, date).values()]
      .map((slot) => slot.pair)
      .filter((pair) => templateLessons(date).some((l) => l.pair === pair && l.group.id === lesson.group.id && l.teacher?.id === teacherId));
    const warnings = withGroup.length > 0 ? [`Вже має пару з цією групою (${withGroup[0]} пара) — краще перестановка`] : [];
    const reasons = dayShape(world, teacherId, date, lesson.pair);
    if (others.length > 0) reasons.push(`Також веде: ${others.map((o) => o.discipline.shortName).join(', ')}`);
    const ownRoom = first.room && roomFree(first.room.id, date, lesson.pair, lesson.id) ? first.room : null;
    options.push(
      draft({
        kind: 'REPLACE_DISCIPLINE',
        title: `${first.discipline.shortName} — ${first.teacher.shortName}`,
        band: warnings.length > 0 ? 'CAUTION' : 'CLEAN',
        reasons,
        warnings,
        teacher: first.teacher,
        discipline: first.discipline,
        room: lesson.room ?? null,
        suggestedRoom: ownRoom ?? lesson.room ?? null,
      }),
    );
  }
  return options;
}

/** 2. Обʼєднати підгрупи: та сама дисципліна для іншої підгрупи на цій парі з присутнім викладачем. */
function mergeOptions(world: World, date: IsoDate, lesson: LessonView): Draft[] {
  if (lesson.subgroup === 0) return [];
  return templateLessons(date)
    .filter(
      (other) =>
        other.group.id === lesson.group.id &&
        other.pair === lesson.pair &&
        other.subgroup > 0 &&
        other.subgroup !== lesson.subgroup &&
        other.discipline.id === lesson.discipline.id &&
        other.teacher != null &&
        other.teacher.id !== lesson.teacher?.id &&
        !isAbsent(world, other.teacher.id, date, lesson.pair) &&
        !decisionFor(world, other.id),
    )
    .map((partner) =>
      draft({
        kind: 'MERGE_SUBGROUPS',
        title: `Об'єднати підгрупи: ${lesson.discipline.shortName} — ${partner.teacher?.shortName ?? ''}`,
        band: 'CLEAN',
        reasons: [`${partner.teacher?.shortName ?? ''} уже веде ${lesson.discipline.shortName} для ${partner.subgroup} підгрупи — візьме обидві`],
        teacher: partner.teacher ?? null,
        discipline: partner.discipline,
        room: partner.room ?? null,
        swapLesson: partner,
        suggestedRoom: partner.room ?? null,
      }),
    );
}

/** 3. Перестановка: пізніша пара групи того ж складу, чий викладач вільний на цій парі. */
function swapOptions(world: World, date: IsoDate, lesson: LessonView): Draft[] {
  const groupDay = groupPairs(world, lesson.group.id, date);
  const lastPair = groupDay.at(-1);
  const sameScope = templateLessons(date).filter((l) => l.group.id === lesson.group.id && l.pair > lesson.pair);
  const options: Draft[] = [];
  for (const later of sameScope) {
    if (later.subgroup !== lesson.subgroup || !later.teacher || later.teacher.id === lesson.teacher?.id) continue;
    // на тій парі в групи має бути саме одне заняття того ж складу, інакше переставляти довелося б кілька
    if (sameScope.filter((l) => l.pair === later.pair).length !== 1) continue;
    if (decisionFor(world, later.id) || swappedAway(world, later.id)) continue;
    if (!isTeacherFree(world, later.teacher.id, date, lesson.pair)) continue;
    const isLast = later.pair === lastPair;
    options.push(
      draft({
        kind: 'SWAP',
        title: `Переставити ${later.pair} пару (${later.discipline.shortName}) на ${lesson.pair}`,
        band: isLast ? 'CLEAN' : 'CAUTION',
        reasons: isLast ? ['Група піде раніше'] : [],
        warnings: isLast ? [] : [`У групи з'явиться вікно на ${later.pair} парі`],
        teacher: later.teacher,
        discipline: later.discipline,
        room: later.room ?? null,
        swapLesson: later,
      }),
    );
  }
  return options;
}

/** 4. «Пари не буде»: легко, коли пара перша, остання чи єдина; посередині дня — вікно в групи. */
function cancelOption(world: World, date: IsoDate, lesson: LessonView): Draft {
  const others = groupPairs(world, lesson.group.id, date).filter((p) => p !== lesson.pair);
  const reasons: string[] = [];
  const warnings: string[] = [];
  if (others.length === 0) reasons.push('Єдина пара групи цього дня');
  else if (lesson.pair < (others[0] ?? 0)) reasons.push('Перша пара: група прийде пізніше');
  else if (lesson.pair > (others.at(-1) ?? 0)) reasons.push('Остання пара: група піде раніше');
  else warnings.push("У групи з'явиться вікно");
  if (lesson.online) reasons.push('Заняття онлайн');
  return draft({ kind: 'CANCEL', title: 'Пари не буде', band: warnings.length > 0 ? 'LAST' : 'EASY_CANCEL', reasons, warnings });
}

/**
 * Варіанти для пари в порядку core: смуга → вид → назва. «Рекомендовано» — лише перший, коли він чистий,
 * а другий гірший за смугою (core ще дивиться на різницю вартостей), і ніколи для минулої пари.
 */
export function optionsFor(world: World, date: IsoDate, lesson: LessonView, today: IsoDate = todayInKyiv()): MockLessonOptions {
  const declined = declinedFor(world, lesson.id);
  const decliners = new Set(declined.map((d) => d.teacher.id));
  const past = compareIsoDates(date, today) < 0;
  const drafts = [
    ...poolOptions(world, date, lesson, decliners),
    ...mergeOptions(world, date, lesson),
    ...swapOptions(world, date, lesson),
    cancelOption(world, date, lesson),
  ].sort(
    (a, b) =>
      BAND_ORDER[a.band] - BAND_ORDER[b.band] ||
      (KIND_ORDER[a.kind] ?? 9) - (KIND_ORDER[b.kind] ?? 9) ||
      UK.compare(a.title, b.title),
  );
  const [first, second] = drafts;
  const clearlyBest = !past && first?.band === 'CLEAN' && (!second || second.band !== 'CLEAN');
  const options = drafts.map((option, index) => ({
    ...option,
    score: Math.max(0, 100 - 10 * index),
    recommended: index === 0 && clearlyBest,
  }));
  return { options, declined, past };
}
