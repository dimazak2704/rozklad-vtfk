import { Dialog as RadixDialog } from 'radix-ui';
import { Outlet } from 'react-router';
import { MOCK_API } from '@/api/client';
import { useLiveConnection } from '@/api/live';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { AdminSidebar } from '@/components/shell/AdminSidebar';
import { AdminTopBar } from '@/components/shell/AdminTopBar';
import { SkipLink } from '@/components/shell/SkipLink';
import { cn } from '@/lib/cn';
import { useSidebarStore } from '@/stores/sidebar';

/**
 * Панель диспетчера: бічна лінія розділів (згортається), верхня панель з робочою датою,
 * палітрою команд і живими оновленнями. Розрахована на планшет і більше; на телефоні меню — шторкою.
 */
export function AdminLayout() {
  const collapsed = useSidebarStore((state) => state.collapsed);
  const toggleCollapsed = useSidebarStore((state) => state.toggleCollapsed);
  const mobileOpen = useSidebarStore((state) => state.mobileOpen);
  const setMobileOpen = useSidebarStore((state) => state.setMobileOpen);
  const { status, isDevBypass } = useCurrentUser();
  // у фіктивного dev-користувача немає токена: потік подій тоді дає лише мок-API
  const live = useLiveConnection(status === 'authenticated' && (!isDevBypass || MOCK_API));

  return (
    <div className="min-h-dvh bg-canvas [--sticky-top:3.5rem]">
      <SkipLink />
      <aside
        className={cn(
          'fixed inset-y-0 start-0 z-40 hidden transition-[width] duration-200 ease-out lg:block',
          collapsed ? 'w-16' : 'w-62',
        )}
      >
        <AdminSidebar collapsed={collapsed} onToggleCollapsed={toggleCollapsed} />
      </aside>

      <RadixDialog.Root open={mobileOpen} onOpenChange={setMobileOpen}>
        <RadixDialog.Portal>
          <RadixDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim lg:hidden" />
          <RadixDialog.Content
            aria-describedby={undefined}
            className="anim-sheet-left fixed inset-y-0 start-0 z-50 w-72 max-w-[85vw] shadow-dialog focus-visible:outline-none lg:hidden"
          >
            <RadixDialog.Title className="sr-only">Розділи панелі диспетчера</RadixDialog.Title>
            <AdminSidebar collapsed={false} onNavigate={() => setMobileOpen(false)} />
          </RadixDialog.Content>
        </RadixDialog.Portal>
      </RadixDialog.Root>

      <div className={cn('flex min-h-dvh flex-col transition-[padding] duration-200 ease-out', collapsed ? 'lg:ps-16' : 'lg:ps-62')}>
        <AdminTopBar live={live} onOpenMenu={() => setMobileOpen(true)} />
        <main id="main" tabIndex={-1} className="w-full flex-1 px-3 py-5 focus:outline-none sm:px-5 lg:px-8 lg:py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
