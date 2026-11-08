/**
 * Збереження, яке сервер відхилив через завершений вхід (401 на POST/PUT/DELETE): сторінка йде на
 * вхід і повертається з порожньою формою. Позначка в sessionStorage переживає перехід через сервер
 * входу, щоб після повернення людина дізналася, що зміну не збережено, і не вважала її збереженою.
 */
const KEY = 'rozklad.auth.unsavedChange';

function storageOrNull(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function noteUnsavedChange(storage: Storage | null = storageOrNull()): void {
  storage?.setItem(KEY, '1');
}

/** `true` один раз після позначки (позначка знімається). */
export function takeUnsavedChange(storage: Storage | null = storageOrNull()): boolean {
  if (!storage?.getItem(KEY)) return false;
  storage.removeItem(KEY);
  return true;
}
