import { Search } from 'lucide-react';
import { NavLink } from 'react-router';
import { navFor, type LayoutKind } from '@/app/routes';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';

const TAB =
  'relative flex min-w-0 flex-1 flex-col items-center justify-center gap-1 pt-1.5 text-meta font-semibold transition-[color] duration-100 ' +
  'focus-visible:outline-2 focus-visible:outline-offset-[-4px] focus-visible:outline-focus';

/** Нижні вкладки на телефоні: публічні сторінки або кабінет викладача + «Пошук». */
export function BottomTabBar({ layout }: { layout: Extract<LayoutKind, 'public' | 'teacher'> }) {
  const tabs = navFor(layout).filter((route) => route.nav.tab);
  const openSearch = useCommandStore((state) => state.setOpen);

  return (
    <nav
      aria-label="Основні розділи"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-default bg-surface pb-[env(safe-area-inset-bottom)] md:hidden"
    >
      <ul className="flex h-(--tabbar-height) items-stretch">
        {tabs.map((route) => (
          <li key={route.id} className="flex min-w-0 flex-1">
            <NavLink
              to={route.path}
              end={route.path === '/' || route.path === '/me'}
              className={({ isActive }) => cn(TAB, isActive ? 'text-accent' : 'text-secondary hover:text-primary')}
            >
              {({ isActive }) => (
                <>
                  {isActive ? <span aria-hidden="true" className="absolute inset-x-5 top-0 h-0.5 rounded-full bg-accent" /> : null}
                  <route.nav.icon aria-hidden="true" className="size-5" />
                  <span className="truncate">{route.nav.label}</span>
                </>
              )}
            </NavLink>
          </li>
        ))}
        <li className="flex min-w-0 flex-1">
          <button type="button" onClick={() => openSearch(true)} className={cn(TAB, 'text-secondary hover:text-primary')}>
            <Search aria-hidden="true" className="size-5" />
            <span>Пошук</span>
          </button>
        </li>
      </ul>
    </nav>
  );
}
