import { Outlet } from 'react-router';
import { MOCK_API } from '@/api/client';
import { useLiveConnection } from '@/api/live';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { BottomTabBar } from '@/components/shell/BottomTabBar';
import { PublicFooter } from '@/components/shell/PublicFooter';
import { PublicHeader } from '@/components/shell/PublicHeader';
import { SkipLink } from '@/components/shell/SkipLink';

/**
 * Кабінет викладача: та сама шапка, що й на сайті, але розділи кабінету («Мій розклад · Відсутності ·
 * Побажання» і «Розклад коледжу»); живі оновлення (SSE), щоб запит на заміну зʼявлявся без перезавантаження.
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
        className="mx-auto w-full max-w-[calc(76rem+2*var(--page-gutter))] flex-1 px-(--page-gutter) pt-6 pb-12 text-body focus:outline-none lg:pt-10"
      >
        <Outlet />
      </main>
      <PublicFooter />
      <BottomTabBar layout="teacher" />
    </div>
  );
}
