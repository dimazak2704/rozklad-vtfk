import { Search, type LucideIcon } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { navFor, type LayoutKind } from '@/app/routes';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';
import { activeNavId } from './activeSection';

const TAB =
  'group/tab relative flex min-w-0 flex-1 flex-col items-center justify-center gap-1 outline-none ' +
  'focus-visible:outline-2 focus-visible:outline-offset-[-4px] focus-visible:outline-focus';

/** Іконка у «таблетці» 56×32: вибрана вкладка — тло selected, яке розкривається з центру. */
function TabFace({ icon: Icon, label, active }: { icon: LucideIcon; label: string; active: boolean }) {
  return (
    <>
      <span className="relative flex h-8 w-14 items-center justify-center">
        <span
          aria-hidden="true"
          className={cn(
            'absolute inset-0 rounded-full bg-selected transition-[transform,opacity] duration-200 ease-out motion-reduce:transition-opacity',
            active ? 'scale-x-100 opacity-100' : 'scale-x-60 opacity-0',
          )}
        />
        <Icon aria-hidden="true" className={cn('relative size-5', active ? 'text-accent' : 'text-muted group-hover/tab:text-secondary')} />
      </span>
      <span className={cn('max-w-full truncate text-meta', active ? 'font-semibold text-primary' : 'font-normal text-secondary')}>
        {label}
      </span>
    </>
  );
}

/** Нижні вкладки на телефоні: публічні сторінки або кабінет викладача + «Пошук». Рівно одна має aria-current. */
export function BottomTabBar({ layout }: { layout: Extract<LayoutKind, 'public' | 'teacher'> }) {
  const tabs = navFor(layout).filter((route) => route.nav.tab);
  const openSearch = useCommandStore((state) => state.setOpen);
  const { pathname } = useLocation();
  const activeId = activeNavId(tabs, pathname);

  return (
    <nav
      aria-label="Основні розділи"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-default bg-surface pb-[env(safe-area-inset-bottom)] md:hidden"
    >
      <ul className="flex h-(--tabbar-height) items-stretch">
        {tabs.map((route) => {
          const active = route.id === activeId;
          return (
            <li key={route.id} className="flex min-w-0 flex-1">
              <Link to={route.path} aria-current={active ? 'page' : undefined} className={TAB}>
                <TabFace icon={route.nav.icon} label={route.nav.label} active={active} />
              </Link>
            </li>
          );
        })}
        <li className="flex min-w-0 flex-1">
          <button type="button" onClick={() => openSearch(true)} className={TAB}>
            <TabFace icon={Search} label="Пошук" active={false} />
          </button>
        </li>
      </ul>
    </nav>
  );
}
