import { create } from 'zustand';

/** Скорочення, яке зараз діє на екрані (для довідки «?»). */
export interface ShortcutInfo {
  keys: string;
  description: string;
  group: string;
  /** Як у `Hotkey.priority`: з кількох однакових клавіш довідка показує ту, що справді спрацює. */
  priority?: number;
  /** Порядок реєстрації: пізніша частина екрана (сторінка) перемагає ранішу (оболонку). */
  order?: number;
}

/** Розділи, що стоять у довідці останніми: спершу — клавіші цієї сторінки. */
export const GENERAL_GROUPS = ['Дії', 'Форми й вікна', 'Загальні'] as const;

interface ShortcutsState {
  entries: Record<string, ShortcutInfo>;
  helpOpen: boolean;
  register: (id: string, info: ShortcutInfo) => void;
  unregister: (id: string) => void;
  setHelpOpen: (open: boolean) => void;
}

export const useShortcutsStore = create<ShortcutsState>()((set) => ({
  entries: {},
  helpOpen: false,
  register: (id, info) => set((state) => ({ entries: { ...state.entries, [id]: info } })),
  unregister: (id) =>
    set((state) => {
      const next = { ...state.entries };
      delete next[id];
      return { entries: next };
    }),
  setHelpOpen: (helpOpen) => set({ helpOpen }),
}));

/**
 * Довідка за розділами: кожна клавіша — один раз, з описом того, що вона зробить саме тут
 * (на сторінці з таблицею «/» — пошук у таблиці, а не палітра). Розділи сторінки — першими,
 * у порядку появи; загальні — наприкінці.
 */
export function helpGroups(entries: Record<string, ShortcutInfo>): Array<[string, Array<{ keys: string; description: string }>]> {
  const winners = new Map<string, ShortcutInfo>();
  for (const entry of Object.values(entries)) {
    // підказки без обробника (Enter у формі) не витісняють і не витісняються справжніми клавішами
    const key = entry.keys.toLowerCase() + ((entry.priority ?? 0) <= -2 ? '#hint' : '');
    const current = winners.get(key);
    const better =
      !current ||
      (entry.priority ?? 0) > (current.priority ?? 0) ||
      ((entry.priority ?? 0) === (current.priority ?? 0) && (entry.order ?? 0) > (current.order ?? 0));
    if (better) winners.set(key, entry);
  }
  const groups = new Map<string, { order: number; items: Array<{ keys: string; description: string }> }>();
  for (const entry of winners.values()) {
    const group = groups.get(entry.group) ?? { order: entry.order ?? 0, items: [] };
    group.order = Math.max(group.order, entry.order ?? 0);
    group.items.push({ keys: entry.keys, description: entry.description });
    groups.set(entry.group, group);
  }
  const general = (name: string) => (GENERAL_GROUPS as readonly string[]).indexOf(name);
  return [...groups.entries()]
    .sort(([a, ga], [b, gb]) => {
      const ia = general(a);
      const ib = general(b);
      if (ia !== ib) return ia - ib;
      return gb.order - ga.order || a.localeCompare(b, 'uk');
    })
    .map(([name, group]) => [name, group.items]);
}
