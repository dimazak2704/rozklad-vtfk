/** Назви клавіш з урахуванням платформи (⌘ на Mac, Ctrl деінде). */
export const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

const KEY_LABELS: Record<string, string> = {
  mod: IS_MAC ? '⌘' : 'Ctrl',
  ctrl: 'Ctrl',
  meta: IS_MAC ? '⌘' : 'Win',
  alt: IS_MAC ? '⌥' : 'Alt',
  shift: 'Shift',
  enter: 'Enter',
  escape: 'Esc',
  esc: 'Esc',
  arrowleft: '←',
  arrowright: '→',
  arrowup: '↑',
  arrowdown: '↓',
  space: 'Пробіл',
  backspace: '⌫',
};

/** Людська назва клавіші: `mod` → Ctrl/⌘, `arrowleft` → ←. */
export function keyLabel(key: string): string {
  const lower = key.toLowerCase();
  return KEY_LABELS[lower] ?? (key.length === 1 ? key.toUpperCase() : key);
}
