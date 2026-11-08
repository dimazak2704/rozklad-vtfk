/**
 * Модель сітки редактора: «сутності» (групи, викладачі, аудиторії) з їхніми заняттями, рядки пар,
 * розкладка занять у клітинці (чисельник/знаменник × доріжки підгруп) і ідентифікатори цілей перетягування.
 */
import type {
  MoveLessonRequest,
  Parity,
  PublicGroupDto,
  PublicRoomDto,
  PublicTeacherDto,
  ShiftNumber,
  ShiftWindowDto,
  TemplateLessonDto,
  Uuid,
  WeekParity,
  Weekday,
} from '@/api/types';
import { roomLabel } from '@/lib/schedule';
import { matchScore } from '@/lib/search';
import { ukCollator } from '@/lib/sort';

export type BoardView = 'group' | 'teacher' | 'room';
export const BOARD_VIEWS: readonly BoardView[] = ['group', 'teacher', 'room'];
export const WEEKDAYS: readonly Weekday[] = [1, 2, 3, 4, 5];
export const WEEK_ROWS: readonly WeekParity[] = ['NUMERATOR', 'DENOMINATOR'];
export const ALL_PAIRS: readonly number[] = [1, 2, 3, 4, 5, 6, 7, 8];

/** Рядок у сітці: група, викладач чи аудиторія разом зі своїми заняттями. */
export interface BoardEntity {
  view: BoardView;
  id: Uuid;
  label: string;
  /** Другий рядок заголовка: «4 курс · 1 зміна», «КМ, СТП». */
  sublabel: string | null;
  /** Для фільтрів: у групи — свій курс і відділення, у викладача й аудиторії — тих груп, з якими вони працюють. */
  courses: ReadonlySet<number>;
  departments: ReadonlySet<string>;
  /** Зміна групи (для вікна пар); у викладача й аудиторії — null. */
  shift: ShiftNumber | null;
  /** Доріжки підгруп у клітинці (лише для груп). */
  lanes: number;
  lessons: TemplateLessonDto[];
}

/** Чи належить заняття сутності. */
export function ownsLesson(view: BoardView, entityId: Uuid, lesson: Pick<TemplateLessonDto, 'group' | 'teacher' | 'room'>): boolean {
  if (view === 'group') return lesson.group.id === entityId;
  if (view === 'teacher') return lesson.teacher?.id === entityId;
  return lesson.room?.id === entityId;
}

export function entityIdOf(view: BoardView, lesson: Pick<TemplateLessonDto, 'group' | 'teacher' | 'room'>): Uuid | null {
  if (view === 'group') return lesson.group.id;
  if (view === 'teacher') return lesson.teacher?.id ?? null;
  return lesson.room?.id ?? null;
}

function bucketBy(lessons: readonly TemplateLessonDto[], key: (lesson: TemplateLessonDto) => Uuid | null): Map<Uuid, TemplateLessonDto[]> {
  const map = new Map<Uuid, TemplateLessonDto[]>();
  for (const lesson of lessons) {
    const id = key(lesson);
    if (!id) continue;
    const list = map.get(id);
    if (list) list.push(lesson);
    else map.set(id, [lesson]);
  }
  return map;
}

/**
 * Сутності подання. Групи — усі з каталогу (порожня група теж має сітку, щоб додати пару); викладачі й
 * аудиторії — лише ті, що мають заняття у версії, бо їх у каталозі сотня і більшість порожні.
 */
export function buildEntities(
  view: BoardView,
  lessons: readonly TemplateLessonDto[],
  catalog: { groups: readonly PublicGroupDto[]; teachers: readonly PublicTeacherDto[]; rooms: readonly PublicRoomDto[] },
): BoardEntity[] {
  const groupsById = new Map(catalog.groups.map((group) => [group.id, group]));
  if (view === 'group') {
    const byGroup = bucketBy(lessons, (l) => l.group.id);
    return [...catalog.groups]
      .sort((a, b) => a.course - b.course || ukCollator.compare(a.code, b.code))
      .map((group) => {
        const own = byGroup.get(group.id) ?? [];
        return {
          view,
          id: group.id,
          label: group.code,
          sublabel: `${group.course} курс · ${group.shift} зміна`,
          courses: new Set([group.course]),
          departments: new Set([group.department]),
          shift: group.shift,
          lanes: Math.max(1, ...own.map((l) => l.subgroup)),
          lessons: own,
        };
      });
  }

  const byOwner = bucketBy(lessons, (l) => entityIdOf(view, l));
  const meta =
    view === 'teacher'
      ? new Map(catalog.teachers.map((t) => [t.id, { label: t.shortName, sublabel: t.cycleCommission }]))
      : new Map(catalog.rooms.map((r) => [r.id, { label: roomLabel(r.name) ?? r.name, sublabel: r.building }]));
  const entities: BoardEntity[] = [];
  for (const [id, own] of byOwner) {
    const sample = own[0] as TemplateLessonDto;
    const known = meta.get(id);
    const fallbackLabel = view === 'teacher' ? (sample.teacher?.shortName ?? '') : (roomLabel(sample.room?.name) ?? '');
    const groups = [...new Set(own.map((l) => l.group.id))].map((groupId) => groupsById.get(groupId)).filter(Boolean) as PublicGroupDto[];
    const disciplines = [...new Set(own.map((l) => l.discipline.shortName))].sort((a, b) => ukCollator.compare(a, b));
    entities.push({
      view,
      id,
      label: known?.label ?? fallbackLabel,
      sublabel: known?.sublabel ?? (view === 'teacher' ? disciplines.slice(0, 4).join(', ') : null),
      courses: new Set(groups.map((g) => g.course)),
      departments: new Set(groups.map((g) => g.department)),
      shift: null,
      lanes: 1,
      lessons: own,
    });
  }
  return entities.sort((a, b) => ukCollator.compare(a.label, b.label));
}

export interface BoardFilters {
  courses: readonly number[];
  department: string | null;
  query: string;
  /** Лише сутності з жорсткими конфліктами. */
  conflictsOnly: boolean;
}

export const EMPTY_FILTERS: BoardFilters = { courses: [], department: null, query: '', conflictsOnly: false };

/**
 * Пошук у сітці. Точний збіг (код групи, прізвище) показує лише його: «4КІ3» не тягне за собою 1КІ3.
 * Коди груп короткі, тож для груп «схоже на одну літеру» — це вже інша група: лише префікс чи входження.
 * Викладачів шукаємо ще й за дисциплінами з другого рядка («СТП» знайде тих, хто її веде).
 */
export function searchEntities(entities: readonly BoardEntity[], query: string): BoardEntity[] {
  const q = query.trim();
  if (!q) return [...entities];
  const texts = (entity: BoardEntity) => (entity.view === 'teacher' ? [entity.label, entity.sublabel] : [entity.label]);
  const scored = entities.map((entity, index) => ({ entity, index, score: matchScore(q, ...texts(entity)) }));
  const best = Math.max(0, ...scored.map((s) => s.score));
  const min = best === 100 ? 100 : entities[0]?.view === 'group' ? 60 : 40;
  return scored
    .filter((s) => s.score >= min)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map((s) => s.entity);
}

/** Фільтри курсу й відділення для викладачів і аудиторій діють через групи, з якими вони працюють. */
export function filterEntities(entities: readonly BoardEntity[], filters: BoardFilters, conflictLessonIds: ReadonlySet<Uuid>): BoardEntity[] {
  const matches = entities.filter((entity) => {
    if (filters.courses.length > 0 && !filters.courses.some((course) => entity.courses.has(course))) return false;
    if (filters.department && !entity.departments.has(filters.department)) return false;
    if (filters.conflictsOnly && !entity.lessons.some((l) => conflictLessonIds.has(l.id))) return false;
    return true;
  });
  return searchEntities(matches, filters.query);
}

// ───────────────────────── Рядки пар ─────────────────────────

export function shiftWindow(windows: readonly ShiftWindowDto[], shift: ShiftNumber | null, weekday: number): ShiftWindowDto | null {
  if (shift === null) return null;
  return windows.find((w) => w.shift === shift && w.weekday === weekday) ?? null;
}

/**
 * Пари-рядки сітки. Група — її вікно зміни на всіх днях (2 зміна: 4–8, бо в пʼятницю 4–6) і пари, що
 * випадають за вікно (їх видно як конфлікт). Викладач і аудиторія — пари від першої до останньої з заняттями,
 * але не менше 1–4: так є куди перетягнути.
 */
export function pairRows(entity: BoardEntity, windows: readonly ShiftWindowDto[]): number[] {
  const pairs = new Set<number>(entity.lessons.map((l) => l.pair));
  if (entity.view === 'group') {
    for (const weekday of WEEKDAYS) {
      const window = shiftWindow(windows, entity.shift, weekday);
      if (window) for (let p = window.firstPair; p <= window.lastPair; p += 1) pairs.add(p);
    }
  } else {
    for (let p = 1; p <= 4; p += 1) pairs.add(p);
  }
  const list = [...pairs].sort((a, b) => a - b);
  const first = list[0] ?? 1;
  const last = list.at(-1) ?? 4;
  return ALL_PAIRS.filter((p) => p >= first && p <= last);
}

/** Чи входить пара у вікно зміни групи (для викладачів і аудиторій вікна немає — завжди так). */
export function inShiftWindow(entity: BoardEntity, windows: readonly ShiftWindowDto[], weekday: number, pair: number): boolean {
  if (entity.view !== 'group') return true;
  const window = shiftWindow(windows, entity.shift, weekday);
  return window !== null && pair >= window.firstPair && pair <= window.lastPair;
}

// ───────────────────────── Розкладка клітинки ─────────────────────────

export interface CellItem {
  lesson: TemplateLessonDto;
  /** 1 — чисельник (верхній рядок), 2 — знаменник; BOTH займає обидва. */
  rowStart: 1 | 2;
  rowSpan: 1 | 2;
  /** Доріжка (з 1) і скільки доріжок займає. */
  laneStart: number;
  laneSpan: number;
}

export interface CellLayout {
  lanes: number;
  items: CellItem[];
}

function rowsOf(parity: Parity): Array<0 | 1> {
  if (parity === 'BOTH') return [0, 1];
  return parity === 'NUMERATOR' ? [0] : [1];
}

function compareInCell(a: TemplateLessonDto, b: TemplateLessonDto): number {
  const parityRank = (p: Parity) => (p === 'BOTH' ? 0 : p === 'NUMERATOR' ? 1 : 2);
  return parityRank(a.parity) - parityRank(b.parity) || a.subgroup - b.subgroup || ukCollator.compare(a.group.code, b.group.code);
}

/**
 * Розкладає заняття клітинки по сітці «2 рядки × доріжки». У групі доріжка — підгрупа (уся група на всю
 * ширину); у викладача й аудиторії — перша вільна. Накладки (конфлікти) отримують додаткові доріжки,
 * тож нічого не ховається одне під одним.
 */
export function layoutCell(lessons: readonly TemplateLessonDto[], view: BoardView, baseLanes = 1): CellLayout {
  const occupied: boolean[][] = [[], []];
  let lanes = Math.max(1, baseLanes);
  const isFree = (rows: Array<0 | 1>, start: number, span: number) =>
    rows.every((row) => Array.from({ length: span }, (_, i) => !occupied[row]?.[start + i]).every(Boolean));
  const take = (rows: Array<0 | 1>, start: number, span: number) => {
    for (const row of rows) for (let i = 0; i < span; i += 1) (occupied[row] as boolean[])[start + i] = true;
  };
  const items: CellItem[] = [];
  for (const lesson of [...lessons].sort(compareInCell)) {
    const rows = rowsOf(lesson.parity);
    let start = -1;
    let span = 1;
    if (view === 'group') {
      const wanted = lesson.subgroup === 0 ? { start: 0, span: lanes } : { start: Math.min(lesson.subgroup, lanes) - 1, span: 1 };
      if (isFree(rows, wanted.start, wanted.span)) {
        start = wanted.start;
        span = wanted.span;
      }
    } else {
      for (let lane = 0; lane < lanes; lane += 1) {
        if (isFree(rows, lane, 1)) {
          start = lane;
          break;
        }
      }
    }
    if (start < 0) {
      // місця немає — накладка: окрема доріжка праворуч
      start = lanes;
      span = 1;
      lanes += 1;
    }
    take(rows, start, span);
    items.push({ lesson, rowStart: rows[0] === 0 ? 1 : 2, rowSpan: rows.length as 1 | 2, laneStart: start + 1, laneSpan: span });
  }
  return { lanes, items };
}

// ───────────────────────── Цілі перетягування ─────────────────────────

/** Ціль перетягування: слот сутності. Для цілих пар — уся клітинка (BOTH), для половинок — рядок. */
export interface DropTarget {
  view: BoardView;
  entityId: Uuid;
  weekday: Weekday;
  pair: number;
  parity: Parity;
}

export function dropTargetId(target: DropTarget): string {
  return ['slot', target.view, target.entityId, target.weekday, target.pair, target.parity].join('|');
}

export function parseDropTargetId(id: string): DropTarget | null {
  const [prefix, view, entityId, weekday, pair, parity] = id.split('|');
  if (prefix !== 'slot' || !view || !entityId || !weekday || !pair || !parity) return null;
  if (!BOARD_VIEWS.includes(view as BoardView)) return null;
  return { view: view as BoardView, entityId, weekday: Number(weekday) as Weekday, pair: Number(pair), parity: parity as Parity };
}

/**
 * Куди можна перетягнути заняття: ціла пара лишається цілою, половинка — половинкою (чисельник ↔ знаменник).
 * Змінити частоту (ціла ↔ половинка) можна в діалозі «Перемістити» — це вже інше навантаження.
 */
export function targetParities(parity: Parity): Parity[] {
  return parity === 'BOTH' ? ['BOTH'] : ['NUMERATOR', 'DENOMINATOR'];
}

/**
 * Чи є сутність ціллю для заняття: у групи й викладача заняття рухається лише в межах своєї сітки;
 * в аудиторіях його можна перенести й в іншу аудиторію (зміниться `roomId`).
 */
export function acceptsLesson(view: BoardView, entityId: Uuid, lesson: TemplateLessonDto): boolean {
  if (view === 'room') return true;
  return ownsLesson(view, entityId, lesson);
}

export function sameSlot(lesson: Pick<TemplateLessonDto, 'weekday' | 'pair' | 'parity'>, target: Pick<DropTarget, 'weekday' | 'pair' | 'parity'>): boolean {
  return lesson.weekday === target.weekday && lesson.pair === target.pair && lesson.parity === target.parity;
}

/** Ціль — це те саме місце, звідки пару взяли (в аудиторіях — ще й та сама аудиторія). */
export function isOrigin(lesson: TemplateLessonDto, target: DropTarget): boolean {
  return sameSlot(lesson, target) && (target.view !== 'room' || lesson.room?.id === target.entityId);
}

/** Тіло переміщення для цілі: в аудиторіях кидок в інший рядок змінює й аудиторію. */
export function moveRequestFor(lesson: TemplateLessonDto, target: DropTarget): MoveLessonRequest {
  const body: MoveLessonRequest = { weekday: target.weekday, pair: target.pair, parity: target.parity };
  if (target.view === 'room' && target.entityId !== lesson.room?.id) body.roomId = target.entityId;
  return body;
}
