/** sessionStorage або null, коли браузер його не дає (приватний режим, заборонені cookie). */
export function sessionStorageOrNull(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}
