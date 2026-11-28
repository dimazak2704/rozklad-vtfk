import { Moon, Sun, SunMoon } from 'lucide-react';
import { useThemeStore, type ThemePreference } from '@/stores/theme';

/** Три варіанти теми: для меню, перемикача й підпису поточного вибору. */
export const THEME_OPTIONS: Array<{ value: ThemePreference; label: string; icon: typeof Sun }> = [
  { value: 'system', label: 'Як у системі', icon: SunMoon },
  { value: 'light', label: 'Світла', icon: Sun },
  { value: 'dark', label: 'Темна', icon: Moon },
];

/** Поточний вибір теми малими літерами: «як у системі», «світла», «темна». */
export function useThemeLabel(): string {
  const preference = useThemeStore((state) => state.preference);
  return THEME_OPTIONS.find((o) => o.value === preference)?.label.toLocaleLowerCase('uk') ?? '';
}
