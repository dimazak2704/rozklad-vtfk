/**
 * Імітація відповіді викладача в Telegram (лише браузерний мок): через кілька секунд після запиту бот
 * «отримує» відповідь і викликає core — так на екрані видно живе оновлення через SSE.
 *
 * Вимкнути (e2e, ручна перевірка): sessionStorage['rozklad.mockTelegram'] = 'manual'; тоді відповідь
 * надсилається явно, як це робить бот: POST /api/internal/changes/{id}/reply.
 */
import type { ReplyDecision, Uuid } from '@/api/types';
import { hashString } from '@/lib/discipline-color';

export const MOCK_TELEGRAM_STORAGE_KEY = 'rozklad.mockTelegram';

const timers = new Map<Uuid, ReturnType<typeof setTimeout>>();

function autoRepliesEnabled(): boolean {
  if (import.meta.env.MODE === 'test' || typeof window === 'undefined') return false;
  try {
    return window.sessionStorage.getItem(MOCK_TELEGRAM_STORAGE_KEY) !== 'manual';
  } catch {
    return true;
  }
}

/**
 * Планує відповідь. Хто відповідає «Не можу» — визначає `decide` (щоб правило жило поруч із даними),
 * сама відповідь іде через `respond`, тобто тим самим шляхом, що й справжній виклик бота.
 */
export function scheduleSimulatedReply(
  changeId: Uuid,
  decide: () => { decision: ReplyDecision; comment: string | null } | null,
  respond: (decision: ReplyDecision, comment: string | null) => void,
): void {
  if (!autoRepliesEnabled()) return;
  cancelSimulatedReply(changeId);
  const delayMs = 6_000 + (hashString(changeId) % 5_000);
  timers.set(
    changeId,
    setTimeout(() => {
      timers.delete(changeId);
      const outcome = decide();
      if (outcome) respond(outcome.decision, outcome.comment);
    }, delayMs),
  );
}

export function cancelSimulatedReply(changeId: Uuid): void {
  const timer = timers.get(changeId);
  if (timer) clearTimeout(timer);
  timers.delete(changeId);
}

/** Детермінована «відмова» для демо: приблизно кожен пʼятий запит або перевантажений день. */
export function simulatedDecision(changeId: Uuid, pairsThatDay: number): { decision: ReplyDecision; comment: string | null } {
  if (pairsThatDay >= 5 || hashString(`decline:${changeId}`) % 5 === 0) {
    return { decision: 'DECLINE', comment: 'Не можу цього дня' };
  }
  return { decision: 'CONFIRM', comment: null };
}
