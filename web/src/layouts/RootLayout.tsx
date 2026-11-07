import { useEffect } from 'react';
import { Outlet, ScrollRestoration, useLocation, useMatches, useNavigate } from 'react-router';
import { onUnauthorized } from '@/api/client';
import { paths } from '@/app/routes';
import { CommandPaletteHost } from '@/components/shell/CommandPaletteHost';
import { NavigationProgress } from '@/components/shell/NavigationProgress';
import { ShortcutsDialog } from '@/components/shell/ShortcutsDialog';
import { ThemeController } from '@/components/shell/ThemeController';
import { ConfirmDialogHost } from '@/components/ui/ConfirmDialog';
import { Toaster } from '@/components/ui/Toaster';

const SITE = 'Розклад ВТФК';

/** Заголовок вкладки з `handle.title` маршруту; сторінка може уточнити через useDocumentTitle. */
function DocumentTitle() {
  const matches = useMatches();
  const title = [...matches].reverse().find((match) => {
    const handle = match.handle as { title?: string } | undefined;
    return Boolean(handle?.title);
  });
  const text = (title?.handle as { title?: string } | undefined)?.title;
  useEffect(() => {
    document.title = text ? `${text} — ${SITE}` : SITE;
  }, [text]);
  return null;
}

/** 401 на захищеній сторінці — сесія скінчилася: ведемо на вхід і повертаємо сюди ж. */
function UnauthorizedRedirect() {
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(
    () =>
      onUnauthorized(() => {
        const path = location.pathname;
        if (path.startsWith('/admin') || path.startsWith('/me')) {
          void navigate(paths.login(path + location.search), { replace: true });
        }
      }),
    [navigate, location.pathname, location.search],
  );
  return null;
}

/** Корінь застосунку: усе, що потрібне на кожній сторінці й залежить від роутера. */
export function RootLayout() {
  return (
    <>
      <ThemeController />
      <DocumentTitle />
      <UnauthorizedRedirect />
      <NavigationProgress />
      <Outlet />
      <CommandPaletteHost />
      <ShortcutsDialog />
      <ConfirmDialogHost />
      <Toaster />
      <ScrollRestoration />
    </>
  );
}
