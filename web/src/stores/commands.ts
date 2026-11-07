import type { LucideIcon } from 'lucide-react';
import { useEffect, useEffectEvent, useId } from 'react';
import { create } from 'zustand';

/** Дія в командній палітрі (Ctrl/⌘+K). */
export interface PaletteCommand {
  /** Унікальний у межах сторінки ключ. */
  id: string;
  label: string;
  /** Розділ палітри: «Дії на сторінці», «Розпорядження». */
  group?: string;
  /** Додаткові слова для пошуку. */
  keywords?: string[];
  icon?: LucideIcon;
  /** Підпис праворуч (скорочення або стан). */
  hint?: string;
  shortcut?: string;
  perform: () => void;
}

interface CommandState {
  open: boolean;
  /** Початковий текст пошуку при відкритті (напр. з мобільної вкладки «Пошук»). */
  initialQuery: string;
  commands: Record<string, PaletteCommand & { scope: string }>;
  setOpen: (open: boolean, initialQuery?: string) => void;
  toggle: () => void;
  register: (scope: string, commands: readonly PaletteCommand[]) => void;
  unregister: (scope: string) => void;
}

export const useCommandStore = create<CommandState>()((set) => ({
  open: false,
  initialQuery: '',
  commands: {},
  setOpen: (open, initialQuery = '') => set({ open, initialQuery }),
  toggle: () => set((state) => ({ open: !state.open, initialQuery: '' })),
  register: (scope, commands) =>
    set((state) => {
      const next = Object.fromEntries(Object.entries(state.commands).filter(([, c]) => c.scope !== scope));
      for (const command of commands) next[`${scope}:${command.id}`] = { ...command, scope };
      return { commands: next };
    }),
  unregister: (scope) =>
    set((state) => ({
      commands: Object.fromEntries(Object.entries(state.commands).filter(([, c]) => c.scope !== scope)),
    })),
}));

/**
 * Реєструє дії сторінки в палітрі, поки сторінка відкрита.
 * `useRegisterCommands([{ id: 'publish', label: 'Опублікувати розпорядження', perform: publish }])`
 * Перереєстрація — лише коли змінюється склад чи підписи дій; `perform` завжди викликає свіжу версію.
 */
export function useRegisterCommands(commands: readonly PaletteCommand[]): void {
  const scope = useId();
  const signature = commands
    .map((c) => [c.id, c.label, c.group, c.hint, c.shortcut, (c.keywords ?? []).join(',')].join('|'))
    .join('\n');
  const run = useEffectEvent((id: string) => commands.find((c) => c.id === id)?.perform());
  const current = useEffectEvent(() => commands);

  useEffect(() => {
    const store = useCommandStore.getState();
    store.register(
      scope,
      current().map((command) => ({ ...command, perform: () => run(command.id) })),
    );
    return () => useCommandStore.getState().unregister(scope);
  }, [signature, scope]);
}
