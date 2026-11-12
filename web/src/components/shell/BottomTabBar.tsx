import { Link, useLocation } from 'react-router';
import { navFor, type LayoutKind } from '@/app/routes';
import { cn } from '@/lib/cn';
import { activeNavId } from './activeSection';

/**
 * Нижні вкладки на телефоні й планшеті (< 1024 px): три розділи сайту або кабінету. Суцільне полотно
 * з лінією згори, без розмиття. Іконка 22 px і підпис; активна — акцентом. Рівно одна має aria-current.
 * Пошук — у шапці (іконка), а не окремою вкладкою.
 */
export function BottomTabBar({ layout }: { layout: Extract<LayoutKind, 'public' | 'teacher'> }) {
  const tabs = navFor(layout).filter((route) => route.nav.tab);
  const { pathname } = useLocation();
  const activeId = activeNavId(tabs, pathname);

  return (
    <nav
      aria-label="Основні розділи"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-separator bg-canvas pb-[env(safe-area-inset-bottom)] lg:hidden"
    >
      <ul className="mx-auto flex h-(--tabbar-height) max-w-xl items-stretch">
        {tabs.map((route) => {
          const active = route.id === activeId;
          const Icon = route.nav.icon;
          return (
            <li key={route.id} className="flex min-w-0 flex-1">
              <Link
                to={route.path}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'group/tab flex min-w-0 flex-1 flex-col items-center justify-center gap-1 pt-1 pb-1.5 outline-none',
                  'transition-colors duration-150 active:bg-active',
                  'focus-visible:outline-2 focus-visible:outline-offset-[-4px] focus-visible:outline-focus',
                  active ? 'text-accent' : 'text-secondary hover-fine:text-primary',
                )}
              >
                <Icon aria-hidden="true" className="size-[1.375rem]" />
                <span className={cn('max-w-full truncate text-meta', active ? 'font-[700]' : 'font-[600]')}>{route.nav.label}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
