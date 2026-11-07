import { useEffect, useEffectEvent, useId } from 'react';
import { IS_MAC } from '@/lib/keys';
import { useShortcutsStore } from '@/stores/shortcuts';

export interface Hotkey {
  /** `mod+k`, `?`, `arrowleft`, `t`, `shift+n`. `mod` — Ctrl, на Mac — ⌘. */
  keys: string;
  handler: (event: KeyboardEvent) => void;
  /** Опис для довідки «?». Без опису скорочення в довідці не показується. */
  description?: string;
  /** Розділ довідки: «Навігація», «Розклад». */
  group?: string;
  /** Спрацьовувати навіть у полях вводу (для mod+k, Esc). */
  allowInInputs?: boolean;
  enabled?: boolean;
}

interface ParsedCombo {
  key: string;
  mod: boolean;
  shift: boolean;
  alt: boolean;
}

export function parseCombo(combo: string): ParsedCombo {
  const parts = combo.toLowerCase().split('+');
  const key = parts.pop() ?? '';
  return { key, mod: parts.includes('mod'), shift: parts.includes('shift'), alt: parts.includes('alt') };
}

type KeyLike = Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'shiftKey' | 'altKey'>;

/**
 * Чи відповідає подія комбінації. Для символів на кшталт «?» Shift не перевіряємо — він частина символу.
 * `physicalKey` — латинська літера фізичної клавіші (щоб «t» працювала і в українській розкладці).
 */
export function matchesCombo(event: KeyLike, combo: string, physicalKey?: string | null): boolean {
  const { key, mod, shift, alt } = parseCombo(combo);
  const modPressed = IS_MAC ? event.metaKey : event.ctrlKey;
  if (mod !== modPressed) return false;
  if (!mod && (event.ctrlKey || event.metaKey)) return false;
  if (alt !== event.altKey) return false;
  const isSymbol = key.length === 1 && !/[a-z0-9]/.test(key);
  if (!isSymbol && shift !== event.shiftKey) return false;
  return event.key.toLowerCase() === key || (physicalKey != null && physicalKey === key);
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (tag === 'INPUT') {
    const type = (target as HTMLInputElement).type;
    return !['checkbox', 'radio', 'button', 'submit', 'reset', 'range', 'color'].includes(type);
  }
  return target.closest('[role="combobox"],[role="textbox"],[cmdk-input]') !== null;
}

/** Клавіша з урахуванням розкладки: KeyT дає «t» і в українській («е»). */
function keyFromCode(event: KeyboardEvent): string | null {
  const match = /^Key([A-Z])$/.exec(event.code);
  return match?.[1] ? match[1].toLowerCase() : null;
}

/**
 * Глобальні клавіатурні скорочення. Не спрацьовують під час набору тексту (крім `allowInInputs`)
 * і коли відкрито модальне вікно. Описані скорочення потрапляють у довідку «?».
 */
export function useHotkeys(hotkeys: readonly Hotkey[]): void {
  const scope = useId();
  const register = useShortcutsStore((state) => state.register);
  const unregister = useShortcutsStore((state) => state.unregister);
  const signature = hotkeys
    .filter((h) => h.enabled !== false)
    .map((h) => `${h.keys}|${h.description ?? ''}|${h.group ?? ''}|${h.allowInInputs ? 1 : 0}`)
    .join('\n');

  const onKeyDown = useEffectEvent((event: KeyboardEvent) => {
    if (event.defaultPrevented || event.isComposing) return;
    const typing = isTypingTarget(event.target);
    const modalOpen = document.querySelector('[role="dialog"][aria-modal="true"],[role="alertdialog"]') !== null;
    for (const hotkey of hotkeys) {
      if (hotkey.enabled === false) continue;
      if (typing && !hotkey.allowInInputs) continue;
      if (modalOpen && !hotkey.allowInInputs) continue;
      if (matchesCombo(event, hotkey.keys, keyFromCode(event))) {
        event.preventDefault();
        hotkey.handler(event);
        return;
      }
    }
  });

  useEffect(() => {
    const listener = (event: KeyboardEvent) => onKeyDown(event);
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, []);

  useEffect(() => {
    const ids: string[] = [];
    signature.split('\n').forEach((line, index) => {
      if (!line) return;
      const [keys = '', description = '', group = ''] = line.split('|');
      if (!description) return;
      const id = `${scope}-${index}`;
      ids.push(id);
      register(id, { keys, description, group: group || 'Загальні' });
    });
    return () => ids.forEach((id) => unregister(id));
  }, [signature, scope, register, unregister]);
}
