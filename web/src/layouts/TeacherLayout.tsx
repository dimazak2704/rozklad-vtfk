import { Outlet } from 'react-router';
import { MOCK_API } from '@/api/client';
import { useLiveConnection } from '@/api/live';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { BottomTabBar } from '@/components/shell/BottomTabBar';
import { PublicHeader } from '@/components/shell/PublicHeader';
import { SkipLink } from '@/components/shell/SkipLink';

/**
 * Кабінет викладача: та сама смуга, що й на сайті, але розділи кабінету; живі оновлення (SSE),
 * щоб запит на заміну зʼявлявся без перезавантаження.
 */
export function TeacherLayout() {
  const { status, isDevBypass } = useCurrentUser();
  // у фіктивного dev-користувача немає токена: потік подій тоді дає лише мок-API
  useLiveConnection(status === 'authenticated' && (!isDevBypass || MOCK_API));

  return (
    <div className="flex min-h-dvh flex-col [--sticky-top:var(--header-height)]">
      <SkipLink />
      <PublicHeader layout="teacher" />
      <main
        id="main"
        tabIndex={-1}
        className="mx-auto w-full max-w-[90rem] flex-1 px-4 pt-5 pb-[calc(var(--tabbar-height)+2rem)] focus:outline-none sm:px-6 sm:pt-7 md:pb-12 lg:px-8"
      >
        <Outlet />
      </main>
      <BottomTabBar layout="teacher" />
    </div>
  );
}
