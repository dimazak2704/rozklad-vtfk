import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

/** Ключ у localStorage; його ж читає вбудований скрипт у index.html, щоб не було спалаху теми. */
export const THEME_STORAGE_KEY = 'rozklad.theme';

interface ThemeState {
  preference: ThemePreference;
  setPreference: (preference: ThemePreference) => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set) => ({
      preference: 'system',
      setPreference: (preference) => set({ preference }),
    }),
    {
      name: THEME_STORAGE_KEY,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({ preference: state.preference }),
      version: 1,
    },
  ),
);

export function systemTheme(): ResolvedTheme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function resolveTheme(preference: ThemePreference): ResolvedTheme {
  return preference === 'system' ? systemTheme() : preference;
}

/**
 * Застосовує тему до <html>. Під час перемикання вимикає CSS-переходи, щоб кольори не «розмазувалися»,
 * а де є View Transitions — нова тема опускається згори, як жалюзі табло.
 */
export function applyTheme(theme: ResolvedTheme, { animate = false }: { animate?: boolean } = {}): void {
  const root = document.documentElement;
  if (root.dataset.theme === theme) return;

  const swap = () => {
    const style = document.createElement('style');
    style.textContent = '*,*::before,*::after{transition:none!important}';
    document.head.append(style);
    root.dataset.theme = theme;
    root.style.colorScheme = theme;
    document
      .querySelector('meta[name="theme-color"]:not([media])')
      ?.setAttribute('content', theme === 'dark' ? '#0d233f' : '#0c3669');
    void root.offsetHeight;
    requestAnimationFrame(() => requestAnimationFrame(() => style.remove()));
  };

  if (animate && typeof document.startViewTransition === 'function') {
    root.dataset.themeSwitching = '';
    const transition = document.startViewTransition(swap);
    void transition.finished.finally(() => {
      delete root.dataset.themeSwitching;
    });
    return;
  }
  swap();
}
