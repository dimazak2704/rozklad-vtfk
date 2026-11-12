import { errorMessage, isApiError } from '@/api/errors';

export const STALE_REASON = 'Хтось змінив це після вас. Перевірте свіжі дані й поправте вручну.';
export const GONE_REASON = 'Цього запису вже немає — його видалили чи змінили після вас.';

export interface UndoVerdict {
  status: 'failed' | 'stale' | 'final';
  reason: string;
}

/**
 * Чому скасування не вдалося, людськими словами, і чи є сенс пробувати ще:
 * - 409 / 404 — стан на сервері вже інший, скасовувати наосліп не можна (`stale`);
 * - 422 — правило не дає (заміну вже внесено в опубліковане розпорядження) — назавжди (`final`);
 * - мережа, 5xx — можна спробувати ще раз (`failed`).
 */
export function classifyUndoError(error: unknown): UndoVerdict {
  if (isApiError(error)) {
    if (error.isStaleData) return { status: 'stale', reason: STALE_REASON };
    if (error.isConflict) return { status: 'stale', reason: errorMessage(error) };
    if (error.isNotFound || error.status === 410) return { status: 'stale', reason: GONE_REASON };
    if (error.status === 422 || error.status === 403) return { status: 'final', reason: errorMessage(error) };
    return { status: 'failed', reason: errorMessage(error) };
  }
  return { status: 'failed', reason: errorMessage(error) };
}
