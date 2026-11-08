import { Outlet } from 'react-router';
import { BottomTabBar } from '@/components/shell/BottomTabBar';
import { PublicFooter } from '@/components/shell/PublicFooter';
import { PublicHeader } from '@/components/shell/PublicHeader';
import { SkipLink } from '@/components/shell/SkipLink';

/**
 * Публічний сайт: шапка-смуга, вміст до 1440 px (основний текст 16 px), підвал; на телефоні — нижні вкладки
 * «Розклад · Заміни · Дзвінки · Пошук».
 */
export function PublicLayout() {
  return (
    // на телефоні шапка не липне, тож липкі заголовки сторінок стають під самий верх
    <div className="flex min-h-dvh flex-col [--sticky-top:0px] md:[--sticky-top:var(--header-height)]">
      <SkipLink />
      <PublicHeader />
      <main id="main" tabIndex={-1} className="mx-auto w-full max-w-[90rem] flex-1 px-4 pt-5 pb-10 text-body focus:outline-none sm:px-6 sm:pt-8 lg:px-8">
        <Outlet />
      </main>
      <PublicFooter />
      <BottomTabBar layout="public" />
    </div>
  );
}
