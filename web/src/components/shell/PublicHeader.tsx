import { Search } from 'lucide-react';
import { NavLink } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { navFor, type LayoutKind } from '@/app/routes';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';
import { IconButton } from '../ui/IconButton';
import { ParityBadge } from '../ui/ParityBadge';
import { Skeleton } from '../ui/Skeleton';
import { SearchTrigger } from './SearchTrigger';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';

/** Парність тижня з /public/meta; поки немає даних — силует, при помилці — нічого. */
function HeaderParity({ compact = false }: { compact?: boolean }) {
  const meta = usePublicMeta();
  if (meta.isPending) return <Skeleton className="h-8 w-40 bg-shell-raised" />;
  if (!meta.data) return null;
  return (
    <ParityBadge
      parity={meta.data.parity}
      weekNumber={compact ? undefined : meta.data.weekNumber}
      prefix={compact ? undefined : 'Зараз'}
      tone="on-shell"
      size={compact ? 'sm' : 'md'}
    />
  );
}

/**
 * Шапка публічного сайту — емальована смуга маршрутного синього: знак, розділи, пошук, парність
 * тижня, тема, вхід. На телефоні розділи переїжджають у нижні вкладки.
 */
export function PublicHeader({ layout = 'public' }: { layout?: Extract<LayoutKind, 'public' | 'teacher'> }) {
  const nav = navFor(layout).filter((route) => route.nav.header);
  const openSearch = useCommandStore((state) => state.setOpen);

  return (
    <header className="sticky top-0 z-30 bg-shell text-on-shell">
      <div className="mx-auto flex h-(--header-height) w-full max-w-[90rem] items-center gap-3 px-4 sm:px-6 lg:px-8">
        <Wordmark to={layout === 'teacher' ? '/me' : '/'} />
        <nav aria-label={layout === 'teacher' ? 'Кабінет викладача' : 'Розділи сайту'} className="ms-2 hidden md:block">
          <ul className="flex items-center gap-0.5">
            {nav.map((route) => (
              <li key={route.id}>
                <NavLink
                  to={route.path}
                  end={route.path === '/' || route.path === '/me'}
                  className={({ isActive }) =>
                    cn(
                      'relative inline-flex h-9 items-center rounded-control px-3 text-ui font-semibold transition-[background-color,color] duration-100',
                      'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-on-shell',
                      isActive ? 'bg-shell-raised text-on-shell' : 'text-on-shell-muted hover:bg-shell-hover hover:text-on-shell',
                    )
                  }
                >
                  {route.nav.label}
                </NavLink>
              </li>
            ))}
            {layout === 'teacher' ? (
              <li>
                <NavLink
                  to="/"
                  end
                  className="inline-flex h-9 items-center rounded-control px-3 text-ui font-semibold text-on-shell-muted hover:bg-shell-hover hover:text-on-shell focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-on-shell"
                >
                  Розклад коледжу
                </NavLink>
              </li>
            ) : null}
          </ul>
        </nav>

        <div className="ms-auto flex min-w-0 items-center gap-1.5 sm:gap-2">
          <SearchTrigger tone="on-shell" className="hidden w-64 md:flex xl:w-80" />
          <div className="hidden lg:block">
            <HeaderParity />
          </div>
          <div className="hidden sm:block lg:hidden">
            <HeaderParity compact />
          </div>
          <IconButton
            label="Пошук"
            icon={<Search aria-hidden="true" className="size-[1.125rem]" />}
            variant="shell"
            className="md:hidden"
            onClick={() => openSearch(true)}
          />
          <ThemeToggle tone="on-shell" />
          <UserMenu tone="on-shell" />
        </div>
      </div>
    </header>
  );
}
