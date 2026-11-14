/**
 * Підготовка ScheduleDto до показу: пари дня з «вікнами», межі сітки тижня, час рядка
 * (п'ятниця має інші дзвінки — такі клітинки показують свій час окремо).
 */
import type { BellSlotDto, DayDto, LessonView, LessonViewKind } from '@/api/types';
import { formatTeacherName } from './teacher-name';

/** Чий це розклад — від цього залежить, що писати в клітинці. */
export type Perspective = 'group' | 'teacher' | 'room';

export type LessonTone = 'regular' | 'change' | 'cancel' | 'add' | 'room' | 'block';

export const LESSON_TONE: Record<LessonViewKind, LessonTone> = {
  REGULAR: 'regular',
  SUBSTITUTED: 'change',
  MOVED: 'change',
  CANCELLED: 'cancel',
  ADDITIONAL: 'add',
  ROOM_CHANGED: 'room',
  BLOCK: 'block',
};

export function isChangedLesson(lesson: Pick<LessonView, 'kind'>): boolean {
  return lesson.kind !== 'REGULAR';
}

export interface DaySlot {
  pair: number;
  startsAt: string | null;
  endsAt: string | null;
  lessons: LessonView[];
  /** Порожня пара між заняттями — «вікно». */
  isWindow: boolean;
  /** Заняття, які перенесли з цієї пари на іншу (див. `vacatedPairs`); у «вікні» — замість слова «вікно». */
  movedAway: LessonView[];
}

/** Заняття за номером пари; у межах пари — за підгрупою (вся група перед підгрупами). */
export function lessonsByPair(lessons: readonly LessonView[]): Map<number, LessonView[]> {
  const map = new Map<number, LessonView[]>();
  for (const lesson of lessons) {
    const list = map.get(lesson.pair) ?? [];
    list.push(lesson);
    map.set(lesson.pair, list);
  }
  for (const list of map.values()) list.sort((a, b) => a.subgroup - b.subgroup);
  return map;
}

/**
 * Пари, які звільнило перенесення: заняття переставили на іншу пару (SWAP), а на старому місці нічого не стоїть.
 * Розпорядження друкує такий слот як «Пари не буде», тож у розкладі він не «вікно». Ключ — звільнена пара,
 * значення — заняття, перенесені з неї (уже на новому місці). Якщо на старому місці щось є (обмін двох пар,
 * інша підгрупа, скасована пара) — пара не звільнена й тут її немає.
 */
export function vacatedPairs(lessons: readonly LessonView[]): Map<number, LessonView[]> {
  const occupied = new Set(lessons.map((lesson) => lesson.pair));
  const map = new Map<number, LessonView[]>();
  for (const lesson of lessons) {
    const from = lesson.kind === 'MOVED' ? lesson.original?.pair : null;
    if (from == null || from === lesson.pair || occupied.has(from)) continue;
    map.set(from, [...(map.get(from) ?? []), lesson]);
  }
  return map;
}

/**
 * Пари дня від першої до останньої із заняттями (або звільненої перенесенням). Порожні пари всередині —
 * «вікна»; звільнені перенесенням несуть `movedAway`. Час береться з дзвінків, а якщо їх немає — із самих занять.
 */
export function daySlots(day: Pick<DayDto, 'lessons'>, bells?: readonly BellSlotDto[] | null): DaySlot[] {
  const byPair = lessonsByPair(day.lessons);
  const vacated = vacatedPairs(day.lessons);
  const pairs = [...byPair.keys(), ...vacated.keys()];
  if (pairs.length === 0) return [];
  const first = Math.min(...pairs);
  const last = Math.max(...pairs);
  const bellByPair = new Map((bells ?? []).map((slot) => [slot.pair, slot]));
  const slots: DaySlot[] = [];
  for (let pair = first; pair <= last; pair += 1) {
    const lessons = byPair.get(pair) ?? [];
    const bell = bellByPair.get(pair);
    const sample = lessons[0];
    slots.push({
      pair,
      startsAt: bell?.startsAt ?? sample?.startsAt ?? null,
      endsAt: bell?.endsAt ?? sample?.endsAt ?? null,
      lessons,
      isWindow: lessons.length === 0,
      movedAway: lessons.length === 0 ? (vacated.get(pair) ?? []) : [],
    });
  }
  return slots;
}

/**
 * Діапазон пар для рядків сітки тижня. Порожній тиждень — null. `withVacated` — рахувати й пари, звільнені
 * перенесенням (сітка на екрані показує їх як «Пари не буде»; друкований бланк — ні).
 */
export function pairRange(
  days: ReadonlyArray<Pick<DayDto, 'lessons'>>,
  { withVacated = false }: { withVacated?: boolean } = {},
): { first: number; last: number } | null {
  let first = Number.POSITIVE_INFINITY;
  let last = Number.NEGATIVE_INFINITY;
  for (const day of days) {
    const pairs = [...day.lessons.map((lesson) => lesson.pair), ...(withVacated ? vacatedPairs(day.lessons).keys() : [])];
    for (const pair of pairs) {
      first = Math.min(first, pair);
      last = Math.max(last, pair);
    }
  }
  return Number.isFinite(first) ? { first, last } : null;
}

/**
 * Час пари «08:30–09:30» або null: без профілю дзвінків core надсилає `startsAt`/`endsAt` = null, і тоді
 * час не показуємо зовсім (а не «null–null»).
 */
export function lessonTime(lesson: Pick<LessonView, 'startsAt' | 'endsAt'>): string | null {
  return lesson.startsAt && lesson.endsAt ? `${lesson.startsAt}–${lesson.endsAt}` : null;
}

/**
 * Найчастіший час пари за тиждень — його пишемо в рядку сітки. Клітинки з іншим часом
 * (у пʼятницю 4 пара о 12:20) показують свій час окремо. Пари без часу (немає профілю дзвінків) пропускаємо.
 */
export function rowTimes(days: ReadonlyArray<Pick<DayDto, 'lessons'>>): Map<number, { startsAt: string; endsAt: string }> {
  const counts = new Map<number, Map<string, number>>();
  for (const day of days) {
    for (const lesson of day.lessons) {
      if (!lesson.startsAt || !lesson.endsAt) continue;
      const key = `${lesson.startsAt}-${lesson.endsAt}`;
      const perPair = counts.get(lesson.pair) ?? new Map<string, number>();
      perPair.set(key, (perPair.get(key) ?? 0) + 1);
      counts.set(lesson.pair, perPair);
    }
  }
  const out = new Map<number, { startsAt: string; endsAt: string }>();
  for (const [pair, perPair] of counts) {
    let bestKey = '';
    let bestCount = -1;
    for (const [key, count] of perPair) {
      if (count > bestCount || (count === bestCount && key < bestKey)) {
        bestKey = key;
        bestCount = count;
      }
    }
    const [startsAt = '', endsAt = ''] = bestKey.split('-');
    out.set(pair, { startsAt, endsAt });
  }
  return out;
}

/**
 * Що показати в другому рядку клітинки залежно від того, чий розклад. Викладач — прізвищем; `ambiguous` —
 * однофамільці цього розкладу (lib/teacher-name `ambiguousGridNames`), їх пишемо з ініціалами.
 */
export function lessonCounterpart(lesson: LessonView, perspective: Perspective, ambiguous?: ReadonlySet<string>): string | null {
  const teacher = lesson.teacher ? formatTeacherName(lesson.teacher, 'grid', { ambiguous }) : null;
  switch (perspective) {
    case 'group':
      return teacher;
    case 'teacher':
      return lesson.group.code;
    case 'room':
      return [lesson.group.code, teacher].filter(Boolean).join(' · ') || null;
  }
}

export function roomLabel(name: string | null | undefined): string | null {
  if (!name) return null;
  // «с.з.» (спортзал) і текстові назви пишемо як є, номери — з «ауд.»
  return /^\d/.test(name) ? `ауд.\u00a0${name}` : name;
}

/** Текст для читача екрана: усе, що видно в клітинці, одним реченням. */
export function lessonSummary(lesson: LessonView, perspective: Perspective = 'group', ambiguous?: ReadonlySet<string>): string {
  const time = lessonTime(lesson);
  const parts: string[] = [`${lesson.pair} пара${time ? `, ${time}` : ''}.`];
  const who = lessonCounterpart(lesson, perspective, ambiguous);
  const room = lesson.room ? `аудиторія ${lesson.room.name}` : null;
  parts.push(`${[lesson.discipline.name || lesson.discipline.shortName, who, room].filter(Boolean).join(', ')}.`);
  if (lesson.subgroup > 0) parts.push(`${lesson.subgroup} підгрупа.`);
  switch (lesson.kind) {
    case 'CANCELLED':
      parts.push('Пари не буде.');
      break;
    case 'SUBSTITUTED': {
      const before = [lesson.original?.discipline?.shortName, lesson.original?.teacher ? formatTeacherName(lesson.original.teacher, 'grid', { ambiguous }) : null]
        .filter(Boolean)
        .join(', ');
      parts.push(before ? `Заміна, замість: ${before}.` : 'Заміна.');
      break;
    }
    case 'MOVED':
      parts.push(lesson.original?.pair ? `Перенесено з ${lesson.original.pair} пари.` : 'Перенесено.');
      break;
    case 'ADDITIONAL':
      parts.push('Додаткова пара.');
      break;
    case 'ROOM_CHANGED':
      parts.push(lesson.original?.room ? `Інша аудиторія, була ${lesson.original.room.name}.` : 'Інша аудиторія.');
      break;
    case 'BLOCK':
      parts.push('Блок пар.');
      break;
    case 'REGULAR':
      break;
  }
  if (lesson.reasonText) parts.push(`Причина: ${lesson.reasonText}${/[.!?]$/.test(lesson.reasonText) ? '' : '.'}`);
  if (lesson.online) parts.push('Дистанційно.');
  return parts.join(' ');
}
