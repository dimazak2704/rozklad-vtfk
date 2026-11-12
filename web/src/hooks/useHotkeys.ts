import { useEffect, useId, useLayoutEffect, useRef } from 'react';
import { IS_MAC } from '@/lib/keys';
import { useShortcutsStore } from '@/stores/shortcuts';

export interface Hotkey {
  /** `mod+k`, `?`, `arrowleft`, `t`, `shift+n`. `mod` — Ctrl, на Mac — ⌘. */
  keys: string;
  /**
   * Обробник. Повертає `false`, якщо цього разу дія не потрібна (наприклад, Enter на кнопці): тоді подія
   * лишається браузеру й іншим скороченням.
   */
  handler: (event: KeyboardEvent) => unknown;
  /** Опис для довідки «?». Без опису скорочення в довідці не показується. */
  description?: string;
  /**
   * Як показати клавіші в довідці, якщо інакше, ніж `keys`: варіанти через кому (`mod+shift+z,mod+y`,
   * `enter,e`) чи діапазон (`1–9`).
   */
  helpKeys?: string;
  /** Розділ довідки: «Навігація», «Розклад». */
  group?: string;
  /** Спрацьовувати навіть у полях вводу й поверх модального вікна (для mod+k, Esc). */
  allowInInputs?: boolean;
  /** Спрацьовувати поверх модального вікна (але не в полях вводу, якщо не задано `allowInInputs`). */
  allowInModal?: boolean;
  enabled?: boolean;
  /**
   * Хто перемагає, коли ту саму клавішу зареєстрували кілька частин екрана: більший пріоритет,
   * за рівного — пізніше змонтована частина (сторінка поверх оболонки). Запасні глобальні дії
   * («/» — палітра, якщо на сторінці немає пошуку) мають −1.
   */
  priority?: number;
}

interface ParsedCombo {
  key: string;
  mod: boolean;
  shift: boolean;
  alt: boolean;
}

export function parseCombo(combo: string): ParsedCombo {
  const parts = combo.toLowerCase().split('+');
  // «mod++» — сама клавіша «+»
  const key = combo.endsWith('++') ? '+' : (parts.pop() ?? '');
  return { key, mod: parts.includes('mod'), shift: parts.includes('shift'), alt: parts.includes('alt') };
}

type KeyLike = Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'shiftKey' | 'altKey'>;

/**
 * Чи відповідає подія комбінації. Для символів на кшталт «?» Shift не перевіряємо — він частина символу.
 * `physicalKey` — символ фізичної клавіші в англійській розкладці (щоб «t» і «/» працювали і в українській).
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

/** Символи клавіш англійської розкладки за `event.code`: без Shift і з Shift. */
const CODE_SYMBOLS: Record<string, readonly [string, string]> = {
  Slash: ['/', '?'],
  Period: ['.', '>'],
  Comma: [',', '<'],
  BracketLeft: ['[', '{'],
  BracketRight: [']', '}'],
  Semicolon: [';', ':'],
  Quote: ["'", '"'],
  Backquote: ['`', '~'],
  Minus: ['-', '_'],
  Equal: ['=', '+'],
  Backslash: ['\\', '|'],
  Digit1: ['1', '!'],
  Digit2: ['2', '@'],
  Digit3: ['3', '#'],
  Digit4: ['4', '$'],
  Digit5: ['5', '%'],
  Digit6: ['6', '^'],
  Digit7: ['7', '&'],
  Digit8: ['8', '*'],
  Digit9: ['9', '('],
  Digit0: ['0', ')'],
};

/**
 * Клавіша з урахуванням розкладки: KeyT дає «t» і в українській («е»), фізична «/» — «/», хоча
 * українська розкладка друкує на ній крапку. Так скорочення не залежать від мови вводу.
 */
export function keyFromCode(event: Pick<KeyboardEvent, 'code' | 'shiftKey'>): string | null {
  const letter = /^Key([A-Z])$/.exec(event.code);
  if (letter?.[1]) return letter[1].toLowerCase();
  const numpad = /^Numpad(\d)$/.exec(event.code);
  if (numpad?.[1]) return numpad[1];
  const symbols = CODE_SYMBOLS[event.code];
  return symbols ? symbols[event.shiftKey ? 1 : 0] : null;
}

export function isTypingTarget(target: EventTarget | null): boolean {
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

/** Відкрите модальне вікно, шторка чи підтвердження — поки воно є, скорочення сторінки мовчать. */
export function isModalOpen(): boolean {
  return document.querySelector('[role="dialog"][aria-modal="true"],[role="alertdialog"]') !== null;
}

// ───────────────────────── Один слухач на застосунок ─────────────────────────

interface Registration {
  order: number;
  current: () => readonly Hotkey[];
}

const registrations = new Map<string, Registration>();
let nextOrder = 0;
let listening = false;

/** Скорочення, яке спрацює на цю подію: найвищий пріоритет, за рівного — найсвіжіша реєстрація. */
export function pickHotkeys(event: KeyboardEvent): Hotkey[] {
  if (event.defaultPrevented || event.isComposing) return [];
  const typing = isTypingTarget(event.target);
  const modal = isModalOpen();
  const physical = keyFromCode(event);
  const candidates: Array<{ hotkey: Hotkey; order: number }> = [];
  for (const registration of registrations.values()) {
    for (const hotkey of registration.current()) {
      if (hotkey.enabled === false) continue;
      if (typing && !hotkey.allowInInputs) continue;
      if (modal && !hotkey.allowInInputs && !hotkey.allowInModal) continue;
      if (matchesCombo(event, hotkey.keys, physical)) candidates.push({ hotkey, order: registration.order });
    }
  }
  candidates.sort((a, b) => (b.hotkey.priority ?? 0) - (a.hotkey.priority ?? 0) || b.order - a.order);
  return candidates.map((candidate) => candidate.hotkey);
}

function dispatch(event: KeyboardEvent): void {
  for (const hotkey of pickHotkeys(event)) {
    if (hotkey.handler(event) === false) continue;
    event.preventDefault();
    return;
  }
}

function register(id: string, registration: Registration): void {
  registrations.set(id, registration);
  if (!listening) {
    window.addEventListener('keydown', dispatch);
    listening = true;
  }
}

function unregister(id: string): void {
  registrations.delete(id);
  if (listening && registrations.size === 0) {
    window.removeEventListener('keydown', dispatch);
    listening = false;
  }
}

/**
 * Клавіатурні скорочення. Не спрацьовують під час набору тексту (крім `allowInInputs`)
 * і коли відкрито модальне вікно. Усі частини екрана слухає один обробник, тож однакову клавішу
 * отримує той, хто важливіший (див. `priority`), а не той, хто раніше підписався. Описані скорочення
 * потрапляють у довідку «?».
 */
export function useHotkeys(hotkeys: readonly Hotkey[]): void {
  const scope = useId();
  const latest = useRef(hotkeys);
  const orderRef = useRef(0);
  const registerHelp = useShortcutsStore((state) => state.register);
  const unregisterHelp = useShortcutsStore((state) => state.unregister);
  const signature = hotkeys
    .filter((h) => h.enabled !== false)
    .map((h) => `${h.helpKeys ?? h.keys}|${h.description ?? ''}|${h.group ?? ''}|${h.priority ?? 0}`)
    .join('\n');

  useLayoutEffect(() => {
    latest.current = hotkeys;
  });

  useEffect(() => {
    nextOrder += 1;
    orderRef.current = nextOrder;
    register(scope, { order: nextOrder, current: () => latest.current });
    return () => unregister(scope);
  }, [scope]);

  useEffect(() => {
    const ids: string[] = [];
    signature.split('\n').forEach((line, index) => {
      if (!line) return;
      const [keys = '', description = '', group = '', priority = '0'] = line.split('|');
      if (!description) return;
      const id = `${scope}-${index}`;
      ids.push(id);
      registerHelp(id, { keys, description, group: group || 'Загальні', priority: Number(priority), order: orderRef.current });
    });
    return () => ids.forEach((id) => unregisterHelp(id));
  }, [signature, scope, registerHelp, unregisterHelp]);
}

/**
 * Рядки довідки без власного обробника: клавіші, які обробляє браузер чи сам компонент
 * (Esc закриває вікно, Enter надсилає форму).
 */
export function useShortcutHints(hints: ReadonlyArray<{ keys: string; description: string; group: string }>): void {
  const scope = useId();
  const registerHelp = useShortcutsStore((state) => state.register);
  const unregisterHelp = useShortcutsStore((state) => state.unregister);
  const signature = hints.map((h) => `${h.keys}|${h.description}|${h.group}`).join('\n');
  useEffect(() => {
    const ids = signature.split('\n').flatMap((line, index) => {
      const [keys = '', description = '', group = ''] = line.split('|');
      if (!keys || !description) return [];
      const id = `${scope}-hint-${index}`;
      registerHelp(id, { keys, description, group, priority: -2, order: 0 });
      return [id];
    });
    return () => ids.forEach((id) => unregisterHelp(id));
  }, [signature, scope, registerHelp, unregisterHelp]);
}

/**
 * Esc у полі пошуку таблиці: перше натискання очищає текст, друге (у вже порожньому полі) виводить
 * фокус на сторінку (`#main`), щоб знову працювали буквені скорочення — як у GitHub і Gmail.
 */
export function handleSearchEscape(
  event: { key: string; preventDefault: () => void; currentTarget: EventTarget | null },
  value: string,
  clear: () => void,
): void {
  if (event.key !== 'Escape') return;
  event.preventDefault();
  if (value) {
    clear();
    return;
  }
  const main = document.getElementById('main');
  if (main) main.focus({ preventScroll: true });
  else if (event.currentTarget instanceof HTMLElement) event.currentTarget.blur();
}
