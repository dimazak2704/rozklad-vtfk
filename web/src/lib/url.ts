/** `decodeURIComponent`, що не падає на зламаній адресі («%E0»): тоді повертає текст як є. */
export function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
