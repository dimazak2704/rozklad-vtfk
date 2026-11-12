import { Dialog as RadixDialog } from 'radix-ui';
import { Outlet } from 'react-router';
import { MOCK_API } from '@/api/client';
import { useLiveConnection } from '@/api/live';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { AdminSidebar } from '@/components/shell/AdminSidebar';
import { AdminTopBar } from '@/components/shell/AdminTopBar';
import { JournalHost } from '@/components/shell/JournalPanel';
import { SkipLink } from '@/components/shell/SkipLink';
import { useReturnFocus } from '@/components/ui/returnFocus';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { cn } from '@/lib/cn';
import { useSidebarStore } from '@/stores/sidebar';

/**
 * Панель диспетчера: бічна панель 232 px на полотні з тонкою лінією, верхня панель лише з робочою датою
 * і станом звʼязку. На 1024–1279 px панель — рейка іконок 64 px з підказками; вужче — шторка з меню.
 * Згортання анімує лише ширину панелі; відступ вмісту перемикається одразу, без «пливучої» сторінки.
 * Вміст — до 1600 px, на ширших екранах зайве стає полями.
 */
export function AdminLayout() {
  const storedCollapsed = useSidebarStore((state) => state.collapsed);
  const toggleCollapsed = useSidebarStore((state) => state.toggleCollapsed);
  const mobileOpen = useSidebarStore((state) => state.mobileOpen);
  const setMobileOpen = useSidebarStore((state) => state.setMobileOpen);
  const wide = useMediaQuery('(min-width: 1280px)', true);
  // на 1024–1279 px — завжди рейка: повна панель забрала б третину робочої ширини
  const collapsed = storedCollapsed || !wide;
  const { status, isDevBypass } = useCurrentUser();
  // у фіктивного dev-користувача немає токена: потік подій тоді дає лише мок-API
  const live = useLiveConnection(status === 'authenticated' && (!isDevBypass || MOCK_API));
  const returnFocus = useReturnFocus();

  return (
    <div className="min-h-dvh bg-canvas [--sticky-top:3.5rem] lg:[--sticky-top:4rem]">
      <SkipLink />
      <JournalHost />
      <aside
        className={cn(
          'fixed inset-y-0 start-0 z-40 hidden transition-[width] duration-200 ease-out motion-reduce:transition-none lg:block',
          collapsed ? 'w-16' : 'w-58',
        )}
      >
        <AdminSidebar collapsed={collapsed} onToggleCollapsed={wide ? toggleCollapsed : undefined} showUser />
      </aside>

      <RadixDialog.Root open={mobileOpen} onOpenChange={setMobileOpen}>
        <RadixDialog.Portal>
          <RadixDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim lg:hidden" />
          <RadixDialog.Content
            aria-describedby={undefined}
            {...returnFocus}
            className="anim-sheet-left fixed inset-y-0 start-0 z-50 w-72 max-w-[85vw] bg-canvas shadow-dialog focus-visible:outline-none lg:hidden"
          >
            <RadixDialog.Title className="sr-only">Розділи панелі диспетчера</RadixDialog.Title>
            <AdminSidebar collapsed={false} onNavigate={() => setMobileOpen(false)} showUser />
          </RadixDialog.Content>
        </RadixDialog.Portal>
      </RadixDialog.Root>

      <div className={cn('flex min-h-dvh flex-col', collapsed ? 'lg:ps-16' : 'lg:ps-58')}>
        <AdminTopBar live={live} onOpenMenu={() => setMobileOpen(true)} />
        <main
          id="main"
          tabIndex={-1}
          className="mx-auto w-full max-w-[calc(100rem+2*var(--page-gutter))] flex-1 px-(--page-gutter) pt-6 pb-12 focus:outline-none lg:px-8 lg:pt-8 xl:px-10"
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
