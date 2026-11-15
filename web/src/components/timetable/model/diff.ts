import type { TemplateLessonDto } from '@/api/types';

export type DiffKind = 'moved' | 'room' | 'roomAssigned' | 'added' | 'removed';

/**
 * Що сталося з парою, яку сервер повернув як «переміщену»: інший день, пару чи тиждень — «Переміщено»; той самий
 * слот, але інша аудиторія — «Інша аудиторія»; аудиторію дали там, де її не було, — «Аудиторію призначено».
 * Ті самі три види рахує й підсумок генерації (`outcomeSentence`).
 */
export function movedKind(from: TemplateLessonDto, to: TemplateLessonDto): 'moved' | 'room' | 'roomAssigned' {
  const sameSlot = from.weekday === to.weekday && from.pair === to.pair && from.parity === to.parity;
  if (!sameSlot) return 'moved';
  return !from.room && to.room ? 'roomAssigned' : 'room';
}
