import { useEffect, useRef } from 'react';
import { Outlet, ScrollRestoration, useLocation, useMatches, useNavigate } from 'react-router';
import { onUnauthorized } from '@/api/client';
import { paths } from '@/app/routes';
import { loginAfterRejection, noteRejectedToken } from '@/auth/rejectedToken';
import { takeUnsavedChange } from '@/auth/unsavedChange';
import { userManager } from '@/auth/userManager';
import { CommandPaletteHost } from '@/components/shell/CommandPaletteHost';
import { NavigationProgress } from '@/components/shell/NavigationProgress';
import { ShortcutsDialog } from '@/components/shell/ShortcutsDialog';
import { ThemeController } from '@/components/shell/ThemeController';
import { ConfirmDialogHost } from '@/components/ui/ConfirmDialog';
import { Toaster } from '@/components/ui/Toaster';
import { notify } from '@/components/ui/toast';

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

/**
 * 401 на захищеній сторінці — сервер не прийняв токен: забуваємо його (інакше сторінка входу побачить
 * «увійшов» і поверне сюди ж — нескінченне коло), ведемо на вхід і повертаємо сюди ж. Кілька запитів
 * сторінки отримують 401 одночасно, а ті, що ще в дорозі, відповідають уже після переходу на вхід, —
 * реагуємо один раз на сторінку (вхід через Keycloak однаково перезавантажує застосунок).
 */
export function UnauthorizedRedirect() {
  const navigate = useNavigate();
  const location = useLocation();
  const handling = useRef(false);
  const protectedPage = isProtected(location.pathname);
  useEffect(() => {
    // нова захищена сторінка (не запізнілі відповіді попередньої) знову може відреагувати на 401
    if (protectedPage) handling.current = false;
  }, [protectedPage, location.pathname]);
  useEffect(
    () =>
      onUnauthorized(() => {
        const path = location.pathname;
        if (!isProtected(path) || handling.current) return;
        handling.current = true;
        const repeated = noteRejectedToken();
        void userManager
          .removeUser()
          .catch(() => undefined)
          .finally(() => {
            void navigate(loginAfterRejection(paths.login(path + location.search), repeated), { replace: true });
          });
      }),
    [navigate, location.pathname, location.search],
  );
  return null;
}

/**
 * Після повторного входу (збереження відхилили з 401, сторінка пішла на вхід) пояснює, що зміну не
 * збережено: форма повернулася порожньою, і без цього диспетчер міг би вважати правку збереженою.
 */
export function UnsavedChangeNotice() {
  const location = useLocation();
  useEffect(() => {
    if (!isProtected(location.pathname)) return;
    if (takeUnsavedChange()) {
      notify.error('Останню зміну не збережено', undefined, {
        description: 'Сесія входу завершилася до збереження. Ви знову увійшли — повторіть дію.',
        duration: 15_000,
      });
    }
  }, [location.pathname]);
  return null;
}

function isProtected(path: string): boolean {
  return path.startsWith('/admin') || path.startsWith('/me');
}

/** Корінь застосунку: усе, що потрібне на кожній сторінці й залежить від роутера. */
export function RootLayout() {
  return (
    <>
      <ThemeController />
      <DocumentTitle />
      <UnauthorizedRedirect />
      <UnsavedChangeNotice />
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
