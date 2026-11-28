import { Outlet } from 'react-router';
import { BottomTabBar } from '@/components/shell/BottomTabBar';
import { PublicFooter } from '@/components/shell/PublicFooter';
import { PublicHeader } from '@/components/shell/PublicHeader';
import { SkipLink } from '@/components/shell/SkipLink';

/**
 * Публічний сайт: шапка на полотні, вміст до 1216 px з полями 16/20/32/48 px (основний текст 16 px), підвал;
 * вужче за 1024 px — нижні вкладки «Розклад · Заміни · Дзвінки». На ширших за 1920 екранах зайве — це поля.
 * Шлях назад на вкладених сторінках дає `PageHeader` (з реєстру маршрутів), а знак завжди веде на головну.
 */
export function PublicLayout() {
  return (
    // шапка липка на всіх ширинах: липкі заголовки сторінок стають під неї
    <div className="flex min-h-dvh flex-col [--sticky-top:var(--header-height)]">
      <SkipLink />
      <PublicHeader />
      {/* вміст не нижчий за екран під шапкою: підвал завжди під згином, тож коли дані приходять і сторінка
          росте, підвал не стрибає вниз через видиму область (зсув макета, CLS) */}
      <main
        id="main"
        tabIndex={-1}
        className="mx-auto min-h-[calc(100dvh-var(--header-height))] w-full max-w-[calc(76rem+2*var(--page-gutter))] flex-1 px-(--page-gutter) pt-6 pb-12 text-body focus:outline-none lg:pt-10"
      >
        <Outlet />
      </main>
      <PublicFooter />
      <BottomTabBar layout="public" />
    </div>
  );
}
