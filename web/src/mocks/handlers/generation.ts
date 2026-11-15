/**
 * Мок генератора розкладу (CONTRACTS §2 Generation). Замість Timefold — простий жадібний «розвʼязувач»:
 * спершу прибирає жорсткі конфлікти (інша вільна аудиторія або інший слот), потім зменшує вікна, рвані дні
 * й пізні пари тими самими правилами, за якими мок рахує конфлікти й метрики (handlers/timetable.ts).
 * Тож «Було / Стало» — справжні числа на даних з Excel, а не вигадані.
 *
 * Мок зважає лише на вікна груп і викладачів, рвані дні й 4-ту пару зміни; решта нових мʼяких правил
 * (стабільність, підгрупи, рівномірні дні…) приходять у запиті й вертаються в задачі, але «розвʼязувач» їх не
 * штрафує. Переставлені пари й таблиця по групах — справжні, з різниці бази й результату.
 *
 * Розвʼязок рахується одразу при запуску, а показується поступово: точка прогресу кожні 2 с, як від scheduler,
 * події `generation.progress` / `generation.completed` ідуть у SSE. Мок завершує роботу щонайпізніше за 12 с,
 * щоб демо й e2e не чекали хвилинами (справжній розвʼязувач працює до ліміту часу).
 */
import { http, HttpResponse } from 'msw';
import { DEFAULT_WEIGHTS, normalizeWeights, WEIGHT_LIMIT } from '@/api/generation-weights';
import type {
  ConstraintAnalysisDto,
  FieldErrorDto,
  GenerationOutcomeDto,
  GroupRef,
  LoadProblemDto,
  GenerationJobCreated,
  GenerationJobDto,
  GenerationJobRequest,
  GenerationJobStatus,
  GenerationProgressPoint,
  GenerationWeights,
  GenerationWeightsWire,
  MetricsDto,
  Parity,
  RoomDto,
  RoomType,
  TemplateLessonDto,
  Uuid,
  Weekday,
} from '@/api/types';
import { formatInstant } from '@/lib/date';
import { emitServerEvent } from '../events';
import { findGroup, findRoom, GROUPS, groupRef, ROOMS, roomRef, SEMESTERS } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, conflict, notFound, problem, readJson } from '../kit';
import { createMockStore } from '../store';
import { ADMIN_ACTOR } from './admin-daily/world';
import { analyzeLessons, cohortsOverlap, groupOf, isLatePair, maxParallelOf, shiftWindowOf, timetableMock } from './timetable';

const MAX_WEIGHT = WEIGHT_LIMIT;
const QUEUE_MS = 600;
const POINT_EVERY_MS = 2000;
const MOCK_MAX_SOLVE_SECONDS = 12;

// ───────────────────────── Розвʼязувач ─────────────────────────

const NUM = 1;
const DEN = 2;
const weekBits = (parity: Parity): number => (parity === 'BOTH' ? NUM | DEN : parity === 'NUMERATOR' ? NUM : DEN);

interface Placement {
  weekday: number;
  pair: number;
  parity: Parity;
  roomId: Uuid | null;
}

interface Unit {
  lesson: TemplateLessonDto;
  weekday: number;
  pair: number;
  parity: Parity;
  roomId: Uuid | null;
  /** У межах області генерації, не закріплене і не частина потоку. */
  movable: boolean;
  group: number;
  teacher: number;
  unitKey: string;
  /** Зміна групи (1 чи 2): для очікування викладача між змінами. */
  shift: number;
}

export interface SolveInput {
  lessons: readonly TemplateLessonDto[];
  /** null — усі групи. */
  scopeGroupIds: ReadonlySet<Uuid> | null;
  weights: GenerationWeights;
}

export interface SolveResult {
  lessons: TemplateLessonDto[];
  /** Оцінка після кожного прийнятого кроку; перша — початкова. */
  trajectory: Array<{ hard: number; soft: number }>;
}

/** Типи аудиторій, що замінюють одна одну (фізкультура — у спортзалі або на майданчику). */
function compatibleTypes(type: RoomType): RoomType[] {
  if (type === 'GYM' || type === 'SPORTS_GROUND') return ['GYM', 'SPORTS_GROUND'];
  return [type];
}

/**
 * Статистика дня з масиву «скільки занять на парі»: [чисельник 0..8, знаменник 0..8], далі (у викладача)
 * стільки ж — скільки з них у груп 2-ї зміни. `windowFirst` — перша пара вікна зміни групи (для 4-ї пари зміни);
 * null — день викладача: тоді проміжок між парою 1-ї зміни і парою 2-ї — очікування, а не вікно
 * (те саме правило, що teacherGaps у моку розкладу).
 */
function dayStats(counts: Int16Array, windowFirst: number | null): { windows: number; broken: boolean; late: number } {
  let firstN = 0;
  let countN = 0;
  let firstD = 0;
  let countD = 0;
  for (let pair = 1; pair <= 8; pair += 1) {
    if ((counts[pair] ?? 0) > 0) {
      if (firstN === 0) firstN = pair;
      countN += 1;
    }
    if ((counts[9 + pair] ?? 0) > 0) {
      if (firstD === 0) firstD = pair;
      countD += 1;
    }
  }
  const windowAt = new Uint8Array(9);
  for (const offset of [0, 9]) {
    let previous = 0;
    for (let pair = 1; pair <= 8; pair += 1) {
      if ((counts[offset + pair] ?? 0) === 0) continue;
      if (previous > 0 && pair - previous > 1 && !(windowFirst === null && isShiftWait(counts, offset, previous, pair))) {
        for (let gap = previous + 1; gap < pair; gap += 1) windowAt[gap] = 1;
      }
      previous = pair;
    }
  }
  let windows = 0;
  for (let pair = 1; pair <= 8; pair += 1) windows += windowAt[pair] ?? 0;
  let late = 0;
  if (windowFirst !== null) {
    for (const offset of [0, 9]) {
      let any = false;
      for (let pair = 1; pair <= 8; pair += 1) if ((counts[offset + pair] ?? 0) > 0 && isLatePair(pair, windowFirst)) any = true;
      if (any) late += 1;
    }
  }
  const broken = countN + countD > 0 && (countN !== countD || firstN !== firstD);
  return { windows, broken, late };
}

/** Усі заняття на парі `a` — групи однієї зміни, на `b` — іншої (лічильники 2-ї зміни — з відступом 18). */
function isShiftWait(counts: Int16Array, offset: number, a: number, b: number): boolean {
  const shift = (pair: number): number => {
    const all = counts[offset + pair] ?? 0;
    const second = counts[18 + offset + pair] ?? 0;
    return second === 0 ? 1 : second === all ? 2 : 0;
  };
  const sa = shift(a);
  const sb = shift(b);
  return sa !== 0 && sb !== 0 && sa !== sb;
}

class Solver {
  private readonly units: Unit[];
  private readonly groupIds: Uuid[] = [];
  private readonly teacherIds: Uuid[] = [];
  private readonly groupDays = new Map<number, Int16Array>();
  private readonly teacherDays = new Map<number, Int16Array>();
  private readonly slotUnits = new Map<number, Set<Unit>>();
  private readonly hardBySlot = new Map<number, number>();
  private readonly rooms: RoomDto[];
  private readonly weights: GenerationWeights;
  hard = 0;
  soft = 0;

  constructor(lessons: readonly TemplateLessonDto[], scope: ReadonlySet<Uuid> | null, weights: GenerationWeights) {
    this.weights = weights;
    const groupIndex = new Map<Uuid, number>();
    const teacherIndex = new Map<Uuid, number>();
    const indexOf = (map: Map<Uuid, number>, list: Uuid[], id: Uuid) => {
      let index = map.get(id);
      if (index === undefined) {
        index = list.length;
        list.push(id);
        map.set(id, index);
      }
      return index;
    };
    this.units = lessons.map((lesson) => ({
      lesson,
      weekday: lesson.weekday,
      pair: lesson.pair,
      parity: lesson.parity,
      roomId: lesson.room?.id ?? null,
      movable: !lesson.pinned && !lesson.streamId && (scope === null || scope.has(lesson.group.id)),
      group: indexOf(groupIndex, this.groupIds, lesson.group.id),
      teacher: lesson.teacher ? indexOf(teacherIndex, this.teacherIds, lesson.teacher.id) : -1,
      unitKey: lesson.streamId ?? lesson.id,
      shift: groupOf(lesson)?.shift ?? 0,
    }));
    this.rooms = [...ROOMS];
    for (const unit of this.units) this.place(unit);
    for (const slot of this.slotUnits.keys()) this.refreshSlot(slot);
    for (const [key, counts] of this.groupDays) this.soft -= this.groupDayCost(key, counts);
    for (const counts of this.teacherDays.values()) this.soft -= this.teacherDayCost(counts);
  }

  // ── облік

  private dayArray(map: Map<number, Int16Array>, key: number): Int16Array {
    let counts = map.get(key);
    if (!counts) {
      counts = new Int16Array(36);
      map.set(key, counts);
    }
    return counts;
  }

  private touch(unit: Unit, delta: 1 | -1): void {
    const bits = weekBits(unit.parity);
    const groupDay = this.dayArray(this.groupDays, unit.group * 10 + unit.weekday);
    const teacherDay = unit.teacher >= 0 ? this.dayArray(this.teacherDays, unit.teacher * 10 + unit.weekday) : null;
    for (const [bit, offset] of [
      [NUM, 0],
      [DEN, 9],
    ] as const) {
      if ((bits & bit) === 0) continue;
      groupDay[offset + unit.pair] = (groupDay[offset + unit.pair] ?? 0) + delta;
      if (teacherDay) {
        teacherDay[offset + unit.pair] = (teacherDay[offset + unit.pair] ?? 0) + delta;
        if (unit.shift === 2) teacherDay[18 + offset + unit.pair] = (teacherDay[18 + offset + unit.pair] ?? 0) + delta;
      }
    }
    const slot = unit.weekday * 10 + unit.pair;
    let set = this.slotUnits.get(slot);
    if (!set) {
      set = new Set();
      this.slotUnits.set(slot, set);
    }
    if (delta > 0) set.add(unit);
    else set.delete(unit);
  }

  private place(unit: Unit): void {
    this.touch(unit, 1);
  }

  private unplace(unit: Unit): void {
    this.touch(unit, -1);
  }

  /** key = група × 10 + день: перша пара вікна зміни для 4-ї пари зміни. */
  private groupDayCost(key: number, counts: Int16Array): number {
    const window = shiftWindowOf(findGroup(this.groupIds[Math.floor(key / 10)] ?? ''), key % 10);
    const stats = dayStats(counts, window?.firstPair ?? 99);
    return this.weights.windows * stats.windows + this.weights.brokenDays * (stats.broken ? 1 : 0) + this.weights.latePairs * stats.late;
  }

  private teacherDayCost(counts: Int16Array): number {
    return this.weights.teacherWindows * dayStats(counts, null).windows;
  }

  /** Скільки жорстких конфліктів у слоті (ті самі правила, що й findHardConflicts у моку розкладу). */
  private slotHard(slot: number): number {
    const units = [...(this.slotUnits.get(slot) ?? [])];
    let count = 0;
    const clash = (list: Unit[], test: (a: Unit, b: Unit) => boolean) =>
      list.some((a, i) => list.some((b, j) => j > i && test(a, b)));
    const overlap = (a: Unit, b: Unit) => (weekBits(a.parity) & weekBits(b.parity)) !== 0;
    for (const list of bucket(units, (u) => (u.teacher >= 0 ? u.teacher : null)).values()) {
      if (clash(list, (a, b) => overlap(a, b) && a.unitKey !== b.unitKey)) count += 1;
    }
    for (const list of bucket(units, (u) => u.group).values()) {
      if (clash(list, (a, b) => overlap(a, b) && cohortsOverlap(a.lesson.subgroup, b.lesson.subgroup))) count += 1;
    }
    for (const [roomId, list] of bucket(units, (u) => u.roomId)) {
      const capacity = maxParallelOf(roomId);
      const keys = [...new Map(list.map((u) => [u.unitKey, u])).values()];
      if ([NUM, DEN].some((bit) => keys.filter((u) => (weekBits(u.parity) & bit) !== 0).length > capacity)) count += 1;
    }
    for (const unit of units) if (!this.inWindow(unit, unit.weekday, unit.pair)) count += 1;
    return count;
  }

  private refreshSlot(slot: number): void {
    const next = this.slotHard(slot);
    this.hard += next - (this.hardBySlot.get(slot) ?? 0);
    this.hardBySlot.set(slot, next);
  }

  private inWindow(unit: Unit, weekday: number, pair: number): boolean {
    const window = shiftWindowOf(findGroup(this.groupIds[unit.group] ?? ''), weekday);
    return window !== null && pair >= window.firstPair && pair <= window.lastPair;
  }

  /** Чи порушує заняття щось на своєму місці (викладач, група, аудиторія, зміна). */
  private violates(unit: Unit): boolean {
    return !this.fits(unit, unit.weekday, unit.pair, unit.parity, unit.roomId);
  }

  /** Чи можна поставити заняття в слот, нічого не порушивши (саме заняття не враховуємо). */
  private fits(unit: Unit, weekday: number, pair: number, parity: Parity, roomId: Uuid | null): boolean {
    if (!this.inWindow(unit, weekday, pair)) return false;
    const bits = weekBits(parity);
    const others = [...(this.slotUnits.get(weekday * 10 + pair) ?? [])].filter((u) => u !== unit && (weekBits(u.parity) & bits) !== 0);
    for (const other of others) {
      if (unit.teacher >= 0 && other.teacher === unit.teacher && other.unitKey !== unit.unitKey) return false;
      if (other.group === unit.group && cohortsOverlap(other.lesson.subgroup, unit.lesson.subgroup)) return false;
    }
    if (roomId) {
      const capacity = maxParallelOf(roomId);
      for (const bit of [NUM, DEN]) {
        if ((bits & bit) === 0) continue;
        const keys = new Set(others.filter((u) => u.roomId === roomId && (weekBits(u.parity) & bit) !== 0).map((u) => u.unitKey));
        if (keys.size >= capacity) return false;
      }
    }
    return true;
  }

  private dayKeys(unit: Unit, weekdays: readonly number[]) {
    return [...new Set(weekdays)].map((weekday) => ({
      groupKey: unit.group * 10 + weekday,
      group: this.dayArray(this.groupDays, unit.group * 10 + weekday),
      teacher: unit.teacher >= 0 ? this.dayArray(this.teacherDays, unit.teacher * 10 + weekday) : null,
    }));
  }

  private localCost(days: ReturnType<Solver['dayKeys']>): number {
    return days.reduce((sum, day) => sum + this.groupDayCost(day.groupKey, day.group) + (day.teacher ? this.teacherDayCost(day.teacher) : 0), 0);
  }

  /** Пересуває заняття в обліку присутності без перерахунку оцінок. */
  private relocate(unit: Unit, to: Placement): void {
    this.unplace(unit);
    unit.weekday = to.weekday;
    unit.pair = to.pair;
    unit.parity = to.parity;
    unit.roomId = to.roomId;
    this.place(unit);
  }

  /** Зміна мʼякої вартості, якщо перенести заняття (мінус — краще); стан не змінюється. */
  private softDelta(unit: Unit, to: Placement): number {
    const origin: Placement = { weekday: unit.weekday, pair: unit.pair, parity: unit.parity, roomId: unit.roomId };
    const days = this.dayKeys(unit, [unit.weekday, to.weekday]);
    const before = this.localCost(days);
    this.relocate(unit, to);
    const after = this.localCost(days);
    this.relocate(unit, origin);
    return after - before;
  }

  /** Приймає перенесення: мʼяка оцінка — за різницею, жорстка — перерахунком двох слотів. */
  private commit(unit: Unit, to: Placement): void {
    const delta = this.softDelta(unit, to);
    const oldSlot = unit.weekday * 10 + unit.pair;
    this.relocate(unit, to);
    this.soft -= delta;
    this.refreshSlot(oldSlot);
    this.refreshSlot(to.weekday * 10 + to.pair);
  }

  private parityOptions(unit: Unit): Parity[] {
    return unit.lesson.parity === 'BOTH' ? ['BOTH'] : ['NUMERATOR', 'DENOMINATOR'];
  }

  private roomCandidates(unit: Unit): Array<Uuid | null> {
    if (!unit.roomId) return [null];
    const current = findRoom(unit.roomId);
    if (!current) return [unit.roomId];
    const types = compatibleTypes(current.type);
    const home = findGroup(this.groupIds[unit.group] ?? '')?.homeRoom?.id ?? null;
    const others = this.rooms.filter((room) => room.id !== current.id && types.includes(room.type)).map((room) => room.id);
    return [current.id, ...(home && others.includes(home) ? [home] : []), ...others.filter((id) => id !== home)];
  }

  /** Найдешевший допустимий слот для заняття; `allDays` — шукати й в інші дні. */
  private bestMove(unit: Unit, allDays: boolean, rooms: Array<Uuid | null>): (Placement & { delta: number }) | null {
    let best: (Placement & { delta: number }) | null = null;
    const weekdays = allDays ? [unit.weekday, 1, 2, 3, 4, 5].filter((d, i, all) => all.indexOf(d) === i) : [unit.weekday];
    for (const weekday of weekdays) {
      const window = shiftWindowOf(groupOf(unit.lesson), weekday);
      if (!window) continue;
      for (let pair = window.firstPair; pair <= window.lastPair; pair += 1) {
        for (const parity of this.parityOptions(unit)) {
          if (weekday === unit.weekday && pair === unit.pair && parity === unit.parity) continue;
          const roomId = rooms.find((room) => this.fits(unit, weekday, pair, parity, room));
          if (roomId === undefined) continue;
          const target: Placement = { weekday, pair, parity, roomId };
          const delta = this.softDelta(unit, target);
          if (!best || delta < best.delta) best = { ...target, delta };
        }
      }
    }
    return best;
  }

  /** Крок 1: заняття з порушеннями — у вільну аудиторію або в інший слот. */
  repairHard(record: () => void): void {
    for (const unit of this.units) {
      if (!unit.movable || !this.violates(unit)) continue;
      const room = this.roomCandidates(unit).find(
        (roomId) => roomId !== unit.roomId && this.fits(unit, unit.weekday, unit.pair, unit.parity, roomId),
      );
      if (room !== undefined) {
        this.commit(unit, { weekday: unit.weekday, pair: unit.pair, parity: unit.parity, roomId: room });
        record();
        continue;
      }
      const move = this.bestMove(unit, true, this.roomCandidates(unit));
      if (move) {
        this.commit(unit, move);
        record();
      }
    }
  }

  /** Крок 2: менше вікон, рваних днів і пізніх пар. Цілі пари — у межах дня, половинки — будь-куди. */
  improveSoft(record: () => void, maxPasses = 4): void {
    for (let pass = 0; pass < maxPasses; pass += 1) {
      let improved = false;
      for (const unit of this.units) {
        if (!unit.movable || this.violates(unit)) continue;
        if (this.localCost(this.dayKeys(unit, [unit.weekday])) === 0) continue;
        const move = this.bestMove(unit, unit.lesson.parity !== 'BOTH', [unit.roomId]);
        if (move && move.delta < 0) {
          this.commit(unit, move);
          record();
          improved = true;
        }
      }
      if (!improved) return;
    }
  }

  lessons(): TemplateLessonDto[] {
    return this.units.map((unit) => {
      const { lesson } = unit;
      const moved = unit.weekday !== lesson.weekday || unit.pair !== lesson.pair || unit.parity !== lesson.parity;
      const roomChanged = unit.roomId !== (lesson.room?.id ?? null);
      if (!moved && !roomChanged) return lesson;
      const room = unit.roomId ? findRoom(unit.roomId) : undefined;
      return {
        ...lesson,
        weekday: unit.weekday as Weekday,
        pair: unit.pair,
        parity: unit.parity,
        room: room ? roomRef(room) : null,
      };
    });
  }
}

function bucket<T, K>(items: readonly T[], key: (item: T) => K | null): Map<K, T[]> {
  const map = new Map<K, T[]>();
  for (const item of items) {
    const k = key(item);
    if (k === null) continue;
    const list = map.get(k);
    if (list) list.push(item);
    else map.set(k, [item]);
  }
  return map;
}

/** Жадібний розвʼязок: оцінка (жорсткі, мʼякі) записується після кожного прийнятого кроку. */
export function solveTimetable(input: SolveInput): SolveResult {
  const solver = new Solver(input.lessons, input.scopeGroupIds, input.weights);
  const trajectory = [{ hard: 0 - solver.hard, soft: solver.soft }];
  const record = () => trajectory.push({ hard: 0 - solver.hard, soft: solver.soft });
  solver.repairHard(record);
  solver.improveSoft(record);
  return { lessons: solver.lessons(), trajectory };
}

// ───────────────────────── Аналіз обмежень ─────────────────────────

/**
 * Аналіз обмежень як від scheduler (genfix §1.3): `constraintId`, `bonus` і `scopeMatchCount` — скільки
 * порушень зачіпає групи області. Половинки в парі — бонус (додатний бал), не порушення.
 */
function constraintAnalysis(
  lessons: readonly TemplateLessonDto[],
  weights: GenerationWeights,
  scope: ReadonlySet<Uuid> | null,
): ConstraintAnalysisDto[] {
  const { hard, metrics } = analyzeLessons(lessons);
  const inScope = (groupId: Uuid) => scope === null || scope.has(groupId);
  const hardCount = (kind: string) => hard.filter((c) => c.kind === kind).length;
  // жорсткий конфлікт «в області», коли зачіпає хоч одну пару групи області (як scopeMatchCount у scheduler)
  const groupOfLesson = new Map(lessons.map((lesson) => [lesson.id, lesson.group.id]));
  const hardInScope = (kind: string) =>
    hard.filter((c) => c.kind === kind && c.lessonIds.some((id) => inScope(groupOfLesson.get(id) ?? ''))).length;
  const scopedPerGroup = metrics.perGroup.filter((row) => inScope(row.group.id));
  const sum = (pick: (row: MetricsDto['perGroup'][number]) => number) => scopedPerGroup.reduce((total, row) => total + pick(row), 0);
  // половинки чисельника й знаменника, що стоять в одному слоті групи
  const halves = new Map<string, { n: boolean; d: boolean; groupId: Uuid }>();
  for (const lesson of lessons) {
    if (lesson.parity === 'BOTH') continue;
    const key = `${lesson.group.id}|${lesson.subgroup}|${lesson.weekday}|${lesson.pair}`;
    const slot = halves.get(key) ?? { n: false, d: false, groupId: lesson.group.id };
    if (lesson.parity === 'NUMERATOR') slot.n = true;
    else slot.d = true;
    halves.set(key, slot);
  }
  const paired = [...halves.values()].filter((slot) => slot.n && slot.d);
  const pairedInScope = paired.filter((slot) => inScope(slot.groupId)).length;
  const rows: Array<{ id: string; constraint: string; matchCount: number; scopeMatchCount: number; hard: number; soft: number; bonus: boolean }> = [
    { id: 'Room conflict', constraint: 'Аудиторія зайнята', matchCount: hardCount('ROOM'), scopeMatchCount: hardInScope('ROOM'), hard: -hardCount('ROOM'), soft: 0, bonus: false },
    { id: 'Teacher conflict', constraint: 'Викладач у двох місцях', matchCount: hardCount('TEACHER'), scopeMatchCount: hardInScope('TEACHER'), hard: -hardCount('TEACHER'), soft: 0, bonus: false },
    { id: 'Group conflict', constraint: 'Накладка в групі', matchCount: hardCount('GROUP'), scopeMatchCount: hardInScope('GROUP'), hard: -hardCount('GROUP'), soft: 0, bonus: false },
    { id: 'Outside shift', constraint: 'Пара поза зміною', matchCount: hardCount('SHIFT'), scopeMatchCount: hardInScope('SHIFT'), hard: -hardCount('SHIFT'), soft: 0, bonus: false },
    // правило коледжу (власник, 15.11.2026): підгрупи однієї дисципліни разом — жорстке; мок їх не розводить
    { id: 'Subgroups not together', constraint: 'Підгрупи дисципліни не одночасно', matchCount: 0, scopeMatchCount: 0, hard: 0, soft: 0, bonus: false },
    // мок не знає, чиї вікна викладача в області, тож рахує їх усі як «в області»
    { id: 'Teacher windows', constraint: 'Вікна викладачів', matchCount: metrics.teacherWindows, scopeMatchCount: metrics.teacherWindows, hard: 0, soft: -weights.teacherWindows * metrics.teacherWindows, bonus: false },
    { id: 'Group windows', constraint: 'Вікна груп', matchCount: metrics.groupWindows, scopeMatchCount: sum((row) => row.windows), hard: 0, soft: -weights.windows * metrics.groupWindows, bonus: false },
    { id: 'Broken day', constraint: 'Рвані дні', matchCount: metrics.brokenDays, scopeMatchCount: sum((row) => row.brokenDays), hard: 0, soft: -weights.brokenDays * metrics.brokenDays, bonus: false },
    { id: 'Late pair', constraint: '4-та пара зміни', matchCount: metrics.latePairs, scopeMatchCount: sum((row) => row.latePairs), hard: 0, soft: -weights.latePairs * metrics.latePairs, bonus: false },
    // правила genfix, що мають метрики в моку (як рядки scheduler з тими самими id і назвами)
    { id: 'Subgroup alone', constraint: 'Підгрупа на парі, а інша без заняття', matchCount: metrics.loneSubgroupSlots ?? 0, scopeMatchCount: sum((row) => row.loneSubgroupSlots ?? 0), hard: 0, soft: -weights.subgroupsTogether * (metrics.loneSubgroupSlots ?? 0), bonus: false },
    { id: 'Group one pair day', constraint: 'День з однією парою в групи', matchCount: metrics.studentOnePairDays ?? 0, scopeMatchCount: sum((row) => row.onePairDays ?? 0), hard: 0, soft: -weights.studentOnePairDay * (metrics.studentOnePairDays ?? 0), bonus: false },
    { id: 'Teacher shift wait', constraint: 'Очікування викладача між змінами', matchCount: metrics.teacherShiftWaits ?? 0, scopeMatchCount: metrics.teacherShiftWaits ?? 0, hard: 0, soft: -weights.teacherShiftWait * (metrics.teacherShiftWaits ?? 0), bonus: false },
    { id: 'Teacher one pair day', constraint: 'День з однією парою у викладача', matchCount: metrics.teacherOnePairDays ?? 0, scopeMatchCount: metrics.teacherOnePairDays ?? 0, hard: 0, soft: -weights.teacherDays * (metrics.teacherOnePairDays ?? 0), bonus: false },
    { id: 'Halves paired', constraint: 'Чисельник і знаменник в одному слоті', matchCount: paired.length, scopeMatchCount: pairedInScope, hard: 0, soft: weights.brokenDays * paired.length, bonus: true },
  ];
  return rows
    .sort((a, b) => a.hard - b.hard || a.soft - b.soft)
    .map((row) => ({
      constraint: row.constraint,
      matchCount: row.matchCount,
      score: `${row.hard}hard/${row.soft}soft`,
      constraintId: row.id,
      bonus: row.bonus,
      scopeMatchCount: row.scopeMatchCount,
    }));
}

/** Скільки пар з базовим місцем переставлено (genfix §3.2 `outcome`): за id заняття в базі й результаті. */
function outcomeOf(base: readonly TemplateLessonDto[], result: readonly TemplateLessonDto[], groups: readonly GroupRef[]): GenerationOutcomeDto {
  const before = new Map(base.map((lesson) => [lesson.id, lesson]));
  const perGroup = new Map(groups.map((group) => [group.id, { group, moved: 0, total: 0 }]));
  let moved = 0;
  let roomChanged = 0;
  let roomAssigned = 0;
  let added = 0;
  let kept = 0;
  let total = 0;
  for (const lesson of result) {
    const initial = before.get(lesson.id);
    const row = perGroup.get(lesson.group.id);
    // як core (§7): рахуються лише вільні пари з базовим місцем; закріплені й поза вибором — `kept`, нові — `added`
    if (!row || initial?.pinned) {
      kept += 1;
      continue;
    }
    if (!initial) {
      added += 1;
      continue;
    }
    total += 1;
    row.total += 1;
    const slotChanged = initial.weekday !== lesson.weekday || initial.pair !== lesson.pair || initial.parity !== lesson.parity;
    if (slotChanged) {
      moved += 1;
      row.moved += 1;
    } else if (initial.room && initial.room.id !== lesson.room?.id) {
      roomChanged += 1;
    } else if (!initial.room && lesson.room) {
      roomAssigned += 1;
    }
  }
  return { moved, roomChanged, total, perGroup: [...perGroup.values()], roomAssigned, added, kept };
}

/**
 * Передперевірка (genfix §3.6, лише GROUP): пар на тиждень у кожної «доріжки» групи (уся група + підгрупа)
 * більше, ніж слотів у її зміні. Пари на тиждень = заняття-тижні / 2 (ціла пара — 1, половинка — 0,5).
 */
export function loadProblems(lessons: readonly TemplateLessonDto[]): LoadProblemDto[] {
  const problems: LoadProblemDto[] = [];
  for (const group of GROUPS) {
    const own = lessons.filter((lesson) => lesson.group.id === group.id);
    if (own.length === 0) continue;
    const slots = [1, 2, 3, 4, 5].reduce((total, weekday) => {
      const window = shiftWindowOf(group, weekday);
      return total + (window ? window.lastPair - window.firstPair + 1 : 0);
    }, 0);
    const weeks = (lesson: TemplateLessonDto) => (lesson.parity === 'BOTH' ? 2 : 1);
    const cohorts = [...new Set(own.map((lesson) => lesson.subgroup).filter((subgroup) => subgroup > 0))];
    for (const cohort of cohorts.length > 0 ? cohorts : [0]) {
      const occurrences = own.filter((lesson) => lesson.subgroup === 0 || lesson.subgroup === cohort).reduce((total, lesson) => total + weeks(lesson), 0);
      if (occurrences <= 2 * slots) continue;
      const pairs = occurrences / 2;
      const label = cohort > 0 ? `${group.code}, підгрупа ${cohort}` : group.code;
      problems.push({
        kind: 'GROUP',
        id: group.id,
        label,
        requiredPairs: pairs,
        availablePairs: slots,
        message: `Група ${label}: ${pairs.toLocaleString('uk-UA')} пари на тиждень, а в її зміні ${slots} слотів.`,
      });
    }
  }
  return problems.sort((a, b) => a.label.localeCompare(b.label, 'uk'));
}

// ───────────────────────── Задачі ─────────────────────────

interface JobRecord {
  id: Uuid;
  semesterId: Uuid;
  /** База: задана версія або опублікована (як у core). */
  baseVersionId: Uuid;
  /** Області: null — усі групи. */
  scopeGroupIds: ReadonlySet<Uuid> | null;
  params: GenerationJobRequest;
  weights: GenerationWeights;
  createdAt: number;
  durationMs: number;
  /** Розвʼязок рахується поза запитом запуску (≈0,2 с), поки задача «в черзі». */
  solution: (SolveResult & { metricsBefore: MetricsDto; baseLessons: readonly TemplateLessonDto[] }) | null;
  /** Коли задача почала «розвʼязуватись» (після черги й підготовки). */
  startedAtMs: number | null;
  cancelledAt: number | null;
  resultVersionId: Uuid | null;
  emittedPoints: number;
  completedEmitted: boolean;
}

const tickers = new Map<Uuid, ReturnType<typeof setInterval>>();

function stopTickers(): void {
  for (const ticker of tickers.values()) clearInterval(ticker);
  tickers.clear();
}

// при скиданні сховищ (тести, window.__rozkladMocks.reset) зупиняємо й таймери задач
const jobs = createMockStore<Map<Uuid, JobRecord>>(() => {
  stopTickers();
  return new Map();
});

/** Частка знайдених покращень на момент `x` (0..1) ходу: швидко на початку, далі плато, як у розвʼязувача. */
const discovered = (x: number) => 1 - (1 - Math.min(1, Math.max(0, x))) ** 3;

type Score = { hard: number; soft: number };

function scoreAt(solution: SolveResult, durationMs: number, elapsedMs: number): Score {
  const { trajectory } = solution;
  const index = Math.round((trajectory.length - 1) * discovered(elapsedMs / durationMs));
  return trajectory[index] ?? (trajectory[0] as Score);
}

function progressUntil(solution: SolveResult, durationMs: number, elapsedMs: number): GenerationProgressPoint[] {
  const points: GenerationProgressPoint[] = [];
  for (let at = 0; at <= elapsedMs; at += POINT_EVERY_MS) points.push({ t: at / 1000, ...scoreAt(solution, durationMs, at) });
  const last = points.at(-1);
  if (elapsedMs >= durationMs && (!last || last.t * 1000 < durationMs)) {
    points.push({ t: durationMs / 1000, ...scoreAt(solution, durationMs, durationMs) });
  }
  return points;
}

const scoreText = (score: Score) => `${score.hard}hard/${score.soft}soft`;
const iso = (ms: number) => new Date(ms).toISOString();

/** Чернетку-результат створюємо один раз, коли задача вперше «помічена» завершеною. */
function materializeResult(job: JobRecord, solution: SolveResult, finishedAt: number): Uuid {
  if (job.resultVersionId) return job.resultVersionId;
  const version = timetableMock.addDraft({
    id: mockId('timetable-version', `generated|${job.id}`),
    semesterId: job.semesterId,
    name: `Згенеровано ${formatInstant(iso(finishedAt))}`,
    source: 'GENERATED',
    lessons: solution.lessons,
  });
  job.resultVersionId = version.id;
  return version.id;
}

function jobStatus(job: JobRecord, now: number): GenerationJobStatus {
  if (job.cancelledAt !== null) return 'CANCELLED';
  if (job.startedAtMs === null || now < job.startedAtMs) return 'QUEUED';
  if (now < job.startedAtMs + job.durationMs) return 'RUNNING';
  return 'SOLVED';
}

/** GenerationJobDto core: адитивні semesterId, createdAt, baseVersionId, timeLimitSeconds, requestedBy. */
function jobView(job: JobRecord, now = Date.now()): GenerationJobDto {
  const status = jobStatus(job, now);
  const { solution, startedAtMs } = job;
  const view: GenerationJobDto = {
    id: job.id,
    semesterId: job.semesterId,
    status,
    createdAt: iso(job.createdAt),
    startedAt: null,
    finishedAt: null,
    bestScore: null,
    progress: [],
    resultVersionId: null,
    baseVersionId: job.baseVersionId,
    timeLimitSeconds: job.params.timeLimitSeconds,
    analysis: null,
    metricsBefore: solution?.metricsBefore ?? null,
    metricsAfter: null,
    error: null,
    requestedBy: ADMIN_ACTOR,
    scope: { groupIds: job.params.scope?.groupIds ?? [], courses: job.params.scope?.courses ?? [] },
    groups: scopeGroups(job.scopeGroupIds),
    weights: job.weights,
    hardScore: null,
    softScore: null,
    outcome: null,
    // нові задачі мока завжди з повним набором ваг (стару задачу з пʼятьма вагами мок не створює)
    legacyWeights: false,
  };
  if (status === 'CANCELLED' && job.cancelledAt !== null) view.finishedAt = iso(job.cancelledAt);
  if (!solution || startedAtMs === null || now < startedAtMs) return view;

  const stoppedAt = job.cancelledAt ?? now;
  const elapsed = Math.max(0, Math.min(stoppedAt - startedAtMs, job.durationMs));
  view.startedAt = iso(startedAtMs);
  view.progress = progressUntil(solution, job.durationMs, elapsed);
  const best = view.progress.at(-1);
  view.bestScore = best ? scoreText(best) : null;
  view.hardScore = best?.hard ?? null;
  view.softScore = best?.soft ?? null;
  if (status === 'SOLVED') {
    const finishedAt = startedAtMs + job.durationMs;
    view.finishedAt = iso(finishedAt);
    view.resultVersionId = materializeResult(job, solution, finishedAt);
    view.analysis = constraintAnalysis(solution.lessons, job.weights, job.scopeGroupIds);
    view.metricsAfter = analyzeLessons(solution.lessons).metrics;
    view.outcome = outcomeOf(solution.baseLessons, solution.lessons, scopeGroups(job.scopeGroupIds));
  }
  return view;
}

/** Події SSE: нові точки прогресу і завершення (раз на секунду, поки задача жива). */
function startTicker(jobId: Uuid): void {
  const ticker = setInterval(() => {
    const job = jobs.get().get(jobId);
    if (!job) {
      clearInterval(ticker);
      tickers.delete(jobId);
      return;
    }
    const view = jobView(job);
    for (const point of view.progress.slice(job.emittedPoints)) {
      emitServerEvent('generation.progress', { jobId, elapsedMs: Math.round(point.t * 1000), hardScore: point.hard, softScore: point.soft });
    }
    job.emittedPoints = view.progress.length;
    if (view.status === 'SOLVED' || view.status === 'CANCELLED') {
      if (!job.completedEmitted) emitServerEvent('generation.completed', view);
      job.completedEmitted = true;
      clearInterval(ticker);
      tickers.delete(jobId);
    }
  }, 1000);
  tickers.set(jobId, ticker);
}

// ───────────────────────── Валідація ─────────────────────────

const WEIGHT_KEYS = Object.keys(DEFAULT_WEIGHTS) as ReadonlyArray<keyof GenerationWeights>;

/** Групи області за курсом і кодом, як `groups` у core. */
function scopeGroups(scope: ReadonlySet<Uuid> | null): GroupRef[] {
  return GROUPS.filter((group) => scope === null || scope.has(group.id))
    .sort((a, b) => a.course - b.course || a.code.localeCompare(b.code, 'uk'))
    .map(groupRef);
}

function validateJob(body: Partial<GenerationJobRequest>): { errors: FieldErrorDto[]; weights: GenerationWeights } {
  const errors: FieldErrorDto[] = [];
  if (!SEMESTERS.some((s) => s.id === body.semesterId)) errors.push({ field: 'semesterId', message: 'Оберіть семестр' });
  const limit = body.timeLimitSeconds;
  if (typeof limit !== 'number' || !Number.isInteger(limit) || limit < 10 || limit > 600) {
    errors.push({ field: 'timeLimitSeconds', message: 'Ліміт часу — від 10 до 600 секунд' });
  }
  const groupIds = body.scope?.groupIds ?? [];
  if (!Array.isArray(groupIds) || groupIds.some((id) => !findGroup(id))) {
    errors.push({ field: 'scope.groupIds', message: 'Серед груп є невідома' });
  }
  const courses = body.scope?.courses ?? [];
  if (!Array.isArray(courses) || courses.some((course) => !Number.isInteger(course) || course < 1 || course > 4)) {
    errors.push({ field: 'scope.courses', message: 'Курс — від 1 до 4' });
  }
  if (body.baseVersionId != null && !timetableMock.find(body.baseVersionId)) {
    errors.push({ field: 'baseVersionId', message: 'Такої версії розкладу немає' });
  }
  // як core: нові ваги необовʼязкові, `teacherWindows` без значення — як `windows`
  const wire: Partial<GenerationWeightsWire> = body.weights ?? {};
  const weights = normalizeWeights({ ...DEFAULT_WEIGHTS, teacherWindows: null, ...wire }) ?? { ...DEFAULT_WEIGHTS };
  for (const key of WEIGHT_KEYS) {
    const value = weights[key];
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > MAX_WEIGHT) {
      errors.push({ field: `weights.${key}`, message: `Вага — від 0 до ${MAX_WEIGHT}` });
    }
  }
  return { errors, weights };
}

function scopeOf(body: GenerationJobRequest): Set<Uuid> | null {
  const groupIds = body.scope?.groupIds ?? [];
  const courses = body.scope?.courses ?? [];
  if (groupIds.length === 0 && courses.length === 0) return null;
  return new Set([...groupIds, ...GROUPS.filter((group) => courses.includes(group.course)).map((group) => group.id)]);
}

// ───────────────────────── Обробники ─────────────────────────

const base = '/admin/generation/jobs';

export const handlers = [
  http.get(apiPath(base), ({ request }) => {
    const semesterId = new URL(request.url).searchParams.get('semesterId');
    const list = [...jobs.get().values()]
      .filter((job) => !semesterId || job.semesterId === semesterId)
      .sort((a, b) => b.createdAt - a.createdAt)
      .map((job) => jobView(job));
    return HttpResponse.json(list);
  }),

  http.post(apiPath(base), async ({ request }) => {
    const body = await readJson<Partial<GenerationJobRequest>>(request);
    const { errors, weights } = validateJob(body);
    if (errors.length > 0) return badRequest('Перевірте параметри генерації.', errors);
    const request_ = body as GenerationJobRequest;
    const now = Date.now();
    const active = [...jobs.get().values()].find(
      (job) => job.semesterId === request_.semesterId && ['QUEUED', 'RUNNING'].includes(jobStatus(job, now)),
    );
    if (active) return conflict('Генерація для цього семестру вже триває. Дочекайтеся завершення або скасуйте її.');

    // без базової версії одиниці беруться з навантаження, а в демо воно виведене з опублікованого розкладу (DOMAIN §12)
    const baseVersion = request_.baseVersionId ? timetableMock.find(request_.baseVersionId) : timetableMock.published(request_.semesterId);
    if (!baseVersion) return notFound('Для семестру немає ні базової, ні опублікованої версії розкладу.');
    // як core: навантаження, що не вміщується в сітку, — 422 до створення задачі
    const overflow = loadProblems(baseVersion.lessons);
    if (overflow.length > 0) {
      return problem({
        status: 422,
        slug: 'business-rule',
        title: 'Порушено правило',
        code: 'GENERATION_LOAD_EXCEEDS_SLOTS',
        detail: `Навантаження не вміщується в сітку занять (${overflow.length}). Виправте навантаження, недоступність викладачів або аудиторії й запустіть генерацію ще раз.`,
        extensions: { problems: overflow },
      });
    }
    const id = mockId('generation-job', crypto.randomUUID());
    const job: JobRecord = {
      id,
      semesterId: request_.semesterId,
      baseVersionId: baseVersion.id,
      scopeGroupIds: scopeOf(request_),
      params: { ...request_, weights },
      weights,
      createdAt: now,
      durationMs: Math.min(request_.timeLimitSeconds, MOCK_MAX_SOLVE_SECONDS) * 1000,
      solution: null,
      startedAtMs: null,
      cancelledAt: null,
      resultVersionId: null,
      emittedPoints: 0,
      completedEmitted: false,
    };
    const store = jobs.get();
    store.set(id, job);
    // рахуємо поза запитом: відповідь 202 приходить одразу, як від справжнього сервера
    setTimeout(() => {
      if (jobs.get() !== store || job.cancelledAt !== null) return;
      const lessons = baseVersion.lessons;
      job.solution = {
        ...solveTimetable({ lessons, scopeGroupIds: job.scopeGroupIds, weights }),
        metricsBefore: analyzeLessons(lessons).metrics,
        baseLessons: lessons,
      };
      job.startedAtMs = Math.max(Date.now(), job.createdAt + QUEUE_MS);
    }, 0);
    startTicker(id);
    const created: GenerationJobCreated = { jobId: id };
    return HttpResponse.json(created, { status: 202, headers: { Location: apiPath(`${base}/${id}`) } });
  }),

  http.get(apiPath(`${base}/:jobId`), ({ params }) => {
    const job = jobs.get().get(String(params.jobId));
    if (!job) return notFound(`Задачу генерації з id ${String(params.jobId)} не знайдено.`);
    return HttpResponse.json(jobView(job));
  }),

  http.post(apiPath(`${base}/:jobId/cancel`), ({ params }) => {
    const job = jobs.get().get(String(params.jobId));
    if (!job) return notFound(`Задачу генерації з id ${String(params.jobId)} не знайдено.`);
    const status = jobStatus(job, Date.now());
    if (status !== 'QUEUED' && status !== 'RUNNING') {
      return conflict('Генерацію вже завершено — скасовувати нічого.');
    }
    job.cancelledAt = Date.now();
    // як core: 202 із задачею
    return HttpResponse.json(jobView(job), { status: 202 });
  }),
];
