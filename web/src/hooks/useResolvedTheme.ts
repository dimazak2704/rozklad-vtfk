import { useSyncExternalStore } from 'react';
import { resolveTheme, useThemeStore, type ResolvedTheme } from '@/stores/theme';

function subscribeSystem(callback: () => void): () => void {
  const query = window.matchMedia('(prefers-color-scheme: dark)');
  query.addEventListener('change', callback);
  return () => query.removeEventListener('change', callback);
}

function systemSnapshot(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Тема, що реально діє: вибір користувача або системна (з підпискою на її зміну). */
export function useResolvedTheme(): ResolvedTheme {
  const preference = useThemeStore((state) => state.preference);
  const systemDark = useSyncExternalStore(subscribeSystem, systemSnapshot, () => false);
  return preference === 'system' ? (systemDark ? 'dark' : 'light') : resolveTheme(preference);
}
