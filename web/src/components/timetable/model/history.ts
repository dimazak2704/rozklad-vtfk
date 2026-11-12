/**
 * Правки чернетки живуть у спільному журналі дій (`@/lib/undo`): кожна знає, як себе скасувати й
 * повторити через API. Тут — облік, який ці записи ділять між собою:
 * - відновлене видалене заняття отримує новий id — старі записи (перенесення, правка) мають
 *   діяти вже на нього;
 * - кожна відповідь сервера дає нову версію заняття — скасування надсилає саме її, а не версію з кешу,
 *   тож чужа правка між вашою дією і скасуванням не перезапишеться мовчки (сервер відповість 409).
 */
import type { MoveLessonRequest, TemplateLessonDto, TemplateLessonRequest, Uuid } from '@/api/types';

export class LessonLedger {
  private readonly aliases = new Map<Uuid, Uuid>();
  private readonly versions = new Map<Uuid, number>();

  /** Поточний id заняття, яке колись мало `id` (після відновлень — найновіший). */
  resolve(id: Uuid): Uuid {
    let current = id;
    const seen = new Set<Uuid>();
    while (this.aliases.has(current) && !seen.has(current)) {
      seen.add(current);
      current = this.aliases.get(current) ?? current;
    }
    return current;
  }

  /** Заняття `from` відновлено як `to`. */
  remap(from: Uuid, to: Pick<TemplateLessonDto, 'id' | 'version'>): void {
    const resolved = this.resolve(from);
    if (resolved !== to.id) this.aliases.set(resolved, to.id);
    this.note(to);
  }

  /** Запамʼятати версію з відповіді сервера. */
  note(lesson: Pick<TemplateLessonDto, 'id' | 'version'>): void {
    if (typeof lesson.version === 'number') this.versions.set(lesson.id, lesson.version);
  }

  /** Остання відома версія заняття (за будь-яким його колишнім id). */
  version(id: Uuid): number | undefined {
    return this.versions.get(this.resolve(id));
  }
}

/** Слот заняття як тіло переміщення (з аудиторією, щоб скасування повернуло й її). */
export function slotOf(lesson: TemplateLessonDto): MoveLessonRequest {
  const slot: MoveLessonRequest = { weekday: lesson.weekday, pair: lesson.pair, parity: lesson.parity };
  if (lesson.room) slot.roomId = lesson.room.id;
  return slot;
}

/** Заняття → тіло POST/PUT (для відновлення видаленого й повернення правки). */
export function toLessonRequest(lesson: TemplateLessonDto): TemplateLessonRequest {
  return {
    weekday: lesson.weekday,
    pair: lesson.pair,
    parity: lesson.parity,
    groupId: lesson.group.id,
    subgroup: lesson.subgroup,
    disciplineId: lesson.discipline.id,
    teacherId: lesson.teacher?.id ?? null,
    roomId: lesson.room?.id ?? null,
    lessonKind: lesson.lessonKind,
    loadItemId: lesson.loadItemId ?? null,
    pinned: lesson.pinned,
    online: lesson.online,
    streamId: lesson.streamId ?? null,
    version: lesson.version,
  };
}
