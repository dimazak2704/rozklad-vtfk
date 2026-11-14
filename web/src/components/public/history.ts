/**
 * Архів лише для персоналу: гість бачить заміни з `publicHistoryFrom` (meta) і розклад з понеділка того тижня.
 * Тексти підказок і межа з відповіді сервера — спільні для «Замін» і сторінок розкладу.
 */
import { isApiError } from '@/api/errors';
import type { IsoDate } from '@/api/types';
import { isIsoDate } from '@/lib/date';

export const CHANGES_HISTORY_HINT = 'Старіші заміни бачить лише персонал коледжу.';
export const SCHEDULE_HISTORY_HINT = 'Старіший розклад бачить лише персонал коледжу.';

/** Чи відповів сервер «архів недоступний» (404 `history-not-public`) — це не збій, а межа для гостя. */
export function isHistoryClosed(error: unknown): boolean {
  return isApiError(error) && error.isHistoryNotPublic;
}

/** Межа з відповіді сервера (вона свіжіша), інакше — з meta; null, якщо невідома. */
export function historyBound(error: unknown, fromMeta: IsoDate | null): IsoDate | null {
  const fromError = isApiError(error) ? error.publicHistoryFrom : undefined;
  return isIsoDate(fromError) ? fromError : fromMeta;
}
