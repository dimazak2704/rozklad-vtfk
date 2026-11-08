/**
 * Сервер відхилив токен (401), який клієнт ще вважає дійсним: ключі realm змінилися, сесію
 * відкликали, gateway не може перевірити підпис. Такий токен треба забути, інакше сторінка входу
 * побачить «увійшов» і поверне назад — і так по колу. Якщо повторний вхід не допоміг (новий токен
 * теж відхилено за хвилину), автоматичний вхід зупиняється і сторінка входу пояснює проблему.
 */
const KEY = 'rozklad.auth.rejectedAt';
export const REJECTION_WINDOW_MS = 60_000;

function storageOrNull(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/** Запамʼятовує відмову; `true`, якщо попередня була щойно (повторний вхід не допоміг). */
export function noteRejectedToken(now: number = Date.now(), storage: Storage | null = storageOrNull()): boolean {
  if (!storage) return false;
  const previous = Number(storage.getItem(KEY));
  storage.setItem(KEY, String(now));
  return Number.isFinite(previous) && previous > 0 && now - previous < REJECTION_WINDOW_MS;
}

/** Після успішної роботи з API (або явного «спробувати ще раз») лічильник скидається. */
export function clearRejectedToken(storage: Storage | null = storageOrNull()): void {
  storage?.removeItem(KEY);
}

/** Адреса сторінки входу: з `rejected=1` вона не відкриває Keycloak сама, а пояснює, що сталося. */
export function loginAfterRejection(loginPath: string, repeated: boolean): string {
  if (!repeated) return loginPath;
  return `${loginPath}${loginPath.includes('?') ? '&' : '?'}rejected=1`;
}
