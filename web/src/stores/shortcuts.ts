import { create } from 'zustand';

/** Скорочення, яке зараз діє на екрані (для довідки «?»). */
export interface ShortcutInfo {
  keys: string;
  description: string;
  group: string;
}

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
