import { useEffect, useRef } from 'react';
import { useResolvedTheme } from '@/hooks/useResolvedTheme';
import { applyTheme } from '@/stores/theme';

/**
 * Синхронізує тему з <html data-theme>. Перший кадр уже має правильну тему (скрипт у index.html),
 * тож анімуємо лише подальші зміни.
 */
export function ThemeController() {
  const theme = useResolvedTheme();
  const firstRun = useRef(true);

  useEffect(() => {
    applyTheme(theme, { animate: !firstRun.current });
    firstRun.current = false;
  }, [theme]);

  return null;
}
