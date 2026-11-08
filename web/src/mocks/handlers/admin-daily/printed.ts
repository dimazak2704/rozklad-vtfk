/**
 * Що друкує розпорядження в колонці «Причина» — як `ReasonCode.printed` у core: без причини для додаткової
 * пари, блоку й заміни аудиторії, «Вичит.» для вичитаної дисципліни, вільний текст для «Інше».
 */
import type { ChangeKind, ReasonCode } from '@/api/types';

const ORDER_TEXT: Record<ReasonCode, string> = {
  ABSENT: 'Відп.',
  MUTUAL: 'Зв.зам.',
  FINISHED: 'Вичит.',
  BUSINESS_TRIP: 'Відр.',
  OTHER: '',
};

export function printedReason(kind: ChangeKind, code: ReasonCode | null, text: string | null): string | null {
  if (kind === 'BLOCK' || kind === 'ADDITIONAL' || kind === 'ROOM_CHANGE') return null;
  if (kind === 'DISCIPLINE_FINISHED') return ORDER_TEXT.FINISHED;
  if (!code) return null;
  if (code === 'OTHER') return text?.trim() || null;
  return ORDER_TEXT[code];
}
