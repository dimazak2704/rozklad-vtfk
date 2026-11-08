import { Dialog as RadixDialog } from 'radix-ui';
import { Outlet } from 'react-router';
import { MOCK_API } from '@/api/client';
import { useLiveConnection } from '@/api/live';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { AdminSidebar } from '@/components/shell/AdminSidebar';
import { AdminTopBar } from '@/components/shell/AdminTopBar';
import { SkipLink } from '@/components/shell/SkipLink';
import { useReturnFocus } from '@/components/ui/returnFocus';
import { cn } from '@/lib/cn';
import { useSidebarStore } from '@/stores/sidebar';

/**
 * Панель диспетчера: нейтральна біла L-рамка — бічна панель розділів (240 px, згортається до 64 px)
 * і верхня панель з робочою датою, палітрою команд і живими оновленнями. На < 1024 px меню — шторкою.
 * Згортання анімує лише ширину панелі; відступ вмісту перемикається одразу, без «пливучої» сторінки.
 */
export function AdminLayout() {
  const collapsed = useSidebarStore((state) => state.collapsed);
  const toggleCollapsed = useSidebarStore((state) => state.toggleCollapsed);
  const mobileOpen = useSidebarStore((state) => state.mobileOpen);
  const setMobileOpen = useSidebarStore((state) => state.setMobileOpen);
  const { status, isDevBypass } = useCurrentUser();
  // у фіктивного dev-користувача немає токена: потік подій тоді дає лише мок-API
  const live = useLiveConnection(status === 'authenticated' && (!isDevBypass || MOCK_API));
  const returnFocus = useReturnFocus();

  return (
    <div className="min-h-dvh bg-canvas [--sticky-top:3.5rem]">
      <SkipLink />
      <aside
        className={cn(
          'fixed inset-y-0 start-0 z-40 hidden transition-[width] duration-200 ease-out motion-reduce:transition-none lg:block',
          collapsed ? 'w-16' : 'w-60',
        )}
      >
        <AdminSidebar collapsed={collapsed} onToggleCollapsed={toggleCollapsed} />
      </aside>

      <RadixDialog.Root open={mobileOpen} onOpenChange={setMobileOpen}>
        <RadixDialog.Portal>
          <RadixDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim lg:hidden" />
          <RadixDialog.Content
            aria-describedby={undefined}
            {...returnFocus}
            className="anim-sheet-left fixed inset-y-0 start-0 z-50 w-72 max-w-[85vw] bg-surface shadow-dialog focus-visible:outline-none lg:hidden"
          >
            <RadixDialog.Title className="sr-only">Розділи панелі диспетчера</RadixDialog.Title>
            <AdminSidebar collapsed={false} onNavigate={() => setMobileOpen(false)} />
          </RadixDialog.Content>
        </RadixDialog.Portal>
      </RadixDialog.Root>

      <div className={cn('flex min-h-dvh flex-col', collapsed ? 'lg:ps-16' : 'lg:ps-60')}>
        <AdminTopBar live={live} onOpenMenu={() => setMobileOpen(true)} />
        <main id="main" tabIndex={-1} className="w-full flex-1 px-3 py-6 focus:outline-none sm:px-5 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
