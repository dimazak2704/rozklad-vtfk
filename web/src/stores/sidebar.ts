import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface SidebarState {
  /** Згорнута бічна панель адмінки (лише іконки). Запамʼятовується. */
  collapsed: boolean;
  /** Відкрита як шторка на вузьких екранах. Не запамʼятовується. */
  mobileOpen: boolean;
  toggleCollapsed: () => void;
  setCollapsed: (collapsed: boolean) => void;
  setMobileOpen: (open: boolean) => void;
}

export const useSidebarStore = create<SidebarState>()(
  persist(
    (set) => ({
      collapsed: false,
      mobileOpen: false,
      toggleCollapsed: () => set((state) => ({ collapsed: !state.collapsed })),
      setCollapsed: (collapsed) => set({ collapsed }),
      setMobileOpen: (mobileOpen) => set({ mobileOpen }),
    }),
    {
      name: 'rozklad.sidebar',
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({ collapsed: state.collapsed }),
      version: 1,
    },
  ),
);
