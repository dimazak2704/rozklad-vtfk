/**
 * Попередня перевірка слота в браузері за вже завантаженими заняттями версії: хто ще займає викладача,
 * групу чи аудиторію в цей час. Точну відповідь дає сервер (`move?dryRun=true`, відповідь на збереження);
 * ця перевірка — для миттєвої підсвітки всіх цілей перетягування і підказок у полях вибору.
 * Спортзал і майданчик приймають кілька груп, а їхньої місткості клієнт не знає, тож їх тут не позначаємо.
 */
import type { Parity, RoomType, ShiftWindowDto, TemplateLessonDto, Uuid } from '@/api/types';
import { roomLabel } from '@/lib/schedule';

export type BusyKind = 'TEACHER' | 'GROUP' | 'ROOM' | 'SHIFT';

export interface Busy {
  kind: BusyKind;
  message: string;
  lessons: TemplateLessonDto[];
}

export interface SlotQuery {
  weekday: number;
  pair: number;
  parity: Parity;
  groupId: Uuid;
  subgroup: number;
  shift: 1 | 2 | null;
  teacherId?: Uuid | null;
  roomId?: Uuid | null;
  roomType?: RoomType | null;
  /** Заняття, яке переносимо чи редагуємо (разом з потоком): його власне місце не рахується зайнятим. */
  ignoreIds?: ReadonlySet<Uuid>;
  streamId?: Uuid | null;
}

const SHARED_ROOM_TYPES: ReadonlySet<RoomType> = new Set(['GYM', 'SPORTS_GROUND']);

export const paritiesOverlap = (a: Parity, b: Parity): boolean => a === 'BOTH' || b === 'BOTH' || a === b;
export const cohortsOverlap = (a: number, b: number): boolean => a === 0 || b === 0 || a === b;

const tag = (lesson: TemplateLessonDto) =>
  `${lesson.group.code}${lesson.subgroup > 0 ? ` (${lesson.subgroup} п/г)` : ''} · ${lesson.discipline.shortName}`;

/** Індекс занять за слотом «день|пара» — щоб перевірка цілі не перебирала всю версію. */
export class SlotIndex {
  private readonly bySlot = new Map<string, TemplateLessonDto[]>();

  constructor(lessons: readonly TemplateLessonDto[]) {
    for (const lesson of lessons) {
      const key = `${lesson.weekday}|${lesson.pair}`;
      const list = this.bySlot.get(key);
      if (list) list.push(lesson);
      else this.bySlot.set(key, [lesson]);
    }
  }

  at(weekday: number, pair: number): readonly TemplateLessonDto[] {
    return this.bySlot.get(`${weekday}|${pair}`) ?? [];
  }

  /** Інші заняття слота, що перетинаються за парністю й не є самим заняттям чи його потоком. */
  private overlapping(query: SlotQuery): TemplateLessonDto[] {
    return this.at(query.weekday, query.pair).filter(
      (other) =>
        !query.ignoreIds?.has(other.id) &&
        !(query.streamId && other.streamId === query.streamId) &&
        paritiesOverlap(other.parity, query.parity),
    );
  }

  teacherLessons(query: SlotQuery): TemplateLessonDto[] {
    if (!query.teacherId) return [];
    return this.overlapping(query).filter((other) => other.teacher?.id === query.teacherId);
  }

  roomLessons(query: SlotQuery): TemplateLessonDto[] {
    if (!query.roomId) return [];
    return this.overlapping(query).filter((other) => other.room?.id === query.roomId);
  }

  groupLessons(query: SlotQuery): TemplateLessonDto[] {
    return this.overlapping(query).filter((other) => other.group.id === query.groupId && cohortsOverlap(other.subgroup, query.subgroup));
  }

  /** Усе, що заважає поставити заняття в слот. Порожньо — слот виглядає вільним. */
  check(query: SlotQuery, windows: readonly ShiftWindowDto[]): Busy[] {
    const issues: Busy[] = [];
    const window = query.shift === null ? undefined : windows.find((w) => w.shift === query.shift && w.weekday === query.weekday);
    if (query.shift !== null && windows.length > 0 && (!window || query.pair < window.firstPair || query.pair > window.lastPair)) {
      issues.push({
        kind: 'SHIFT',
        message: window
          ? `Поза вікном ${query.shift} зміни: пари ${window.firstPair}–${window.lastPair}`
          : `У ${query.shift} зміни цього дня пар немає`,
        lessons: [],
      });
    }
    const group = this.groupLessons(query);
    if (group.length > 0) {
      issues.push({ kind: 'GROUP', message: `Група вже має пару: ${group.map(tag).join(', ')}`, lessons: group });
    }
    const teacher = this.teacherLessons(query);
    if (teacher.length > 0) {
      const name = teacher[0]?.teacher?.shortName ?? 'Викладач';
      issues.push({ kind: 'TEACHER', message: `${name} у цей час веде ${teacher.map(tag).join(', ')}`, lessons: teacher });
    }
    if (!query.roomType || !SHARED_ROOM_TYPES.has(query.roomType)) {
      const room = this.roomLessons(query);
      if (room.length > 0) {
        const name = roomLabel(room[0]?.room?.name) ?? 'Аудиторія';
        issues.push({ kind: 'ROOM', message: `${name} у цей час зайнята: ${room.map(tag).join(', ')}`, lessons: room });
      }
    }
    return issues;
  }
}

/** Заняття разом з учасниками його потоку (рухаються разом). */
export function withStreamMates(lesson: TemplateLessonDto, lessons: readonly TemplateLessonDto[]): Set<Uuid> {
  const ids = new Set([lesson.id]);
  if (lesson.streamId) for (const other of lessons) if (other.streamId === lesson.streamId) ids.add(other.id);
  return ids;
}
