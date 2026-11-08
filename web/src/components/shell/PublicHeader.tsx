import { Link, useLocation } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { navFor, type LayoutKind } from '@/app/routes';
import { cn } from '@/lib/cn';
import { capitalize, PARITY_HINT, PARITY_LABEL } from '@/lib/labels';
import { ParityBadge, ParityMark } from '../ui/ParityBadge';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/Popover';
import { Skeleton } from '../ui/Skeleton';
import { activeNavId } from './activeSection';
import { SearchTrigger } from './SearchTrigger';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';

/** Парність тижня з /public/meta текстом: «Знаменник · 10-й тиждень». Поки немає даних — силует, при помилці — нічого. */
function HeaderParity({ withWeek }: { withWeek: boolean }) {
  const meta = usePublicMeta();
  if (meta.isPending) return <Skeleton className="h-5 w-36 bg-shell-hover" />;
  if (!meta.data) return null;
  return <ParityBadge parity={meta.data.parity} weekNumber={withWeek ? meta.data.weekNumber : undefined} tone="on-shell" />;
}

/** Телефон: лише знак і слово, конденсованим, — найважливіший факт для студента; тап пояснює правило. */
function CompactParity() {
  const meta = usePublicMeta();
  if (!meta.data) return null;
  const label = capitalize(PARITY_LABEL[meta.data.parity]);
  return (
    <Popover>
      <PopoverTrigger
        aria-label={`${label}, ${meta.data.weekNumber}-й тиждень. Як чергуються тижні`}
        className="inline-flex h-11 items-center gap-1.5 rounded-control px-2 text-on-shell focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus-on-shell"
      >
        <ParityMark parity={meta.data.parity} className="h-4 w-3.5" />
        <span className="font-condensed text-cell font-bold">{label}</span>
      </PopoverTrigger>
      <PopoverContent width="sm" align="end" title={`Цього тижня — ${PARITY_LABEL[meta.data.parity]}, ${meta.data.weekNumber}-й тиждень`}>
        <p className="text-ui text-secondary">{PARITY_HINT[meta.data.parity]}</p>
      </PopoverContent>
    </Popover>
  );
}

/**
 * Шапка публічного сайту — одна спокійна емальована смуга: знак, три розділи текстом, одне поле пошуку,
 * парність тижня словами, тема й вхід. На телефоні смуга нижча й не липне — постійна лише нижня панель.
 */
export function PublicHeader({ layout = 'public' }: { layout?: Extract<LayoutKind, 'public' | 'teacher'> }) {
  const nav = navFor(layout).filter((route) => route.nav.header);
  const { pathname } = useLocation();
  const activeId = activeNavId(nav, pathname);

  return (
    <header className="z-30 border-b border-transparent bg-shell text-on-shell md:sticky md:top-0 dark:border-shell">
      <div className="mx-auto flex h-13 w-full max-w-[90rem] items-center gap-2 ps-4 pe-2 md:h-(--header-height) md:gap-4 md:px-6 lg:px-8">
        <Wordmark to={layout === 'teacher' ? '/me' : '/'} />
        <nav aria-label={layout === 'teacher' ? 'Кабінет викладача' : 'Розділи сайту'} className="ms-3 hidden self-stretch md:block">
          <ul className="flex h-full items-stretch">
            {nav.map((route) => {
              const active = route.id === activeId;
              return (
                <li key={route.id} className="flex">
                  <Link
                    to={route.path}
                    aria-current={active ? 'page' : undefined}
                    className={cn(
                      'group/nav relative flex items-center px-0.5 outline-none',
                      // індикатор 3 px урівень з нижнім краєм смуги
                      active &&
                        'after:absolute after:inset-x-3.5 after:bottom-0 after:h-[3px] after:rounded-t-[2px] after:bg-[var(--text-color-on-shell)]',
                    )}
                  >
                    <span
                      className={cn(
                        'inline-flex h-9 items-center rounded-control px-3 text-ui transition-[background-color,color] duration-100',
                        'group-focus-visible/nav:outline-2 group-focus-visible/nav:outline-offset-2 group-focus-visible/nav:outline-focus-on-shell',
                        active
                          ? 'font-semibold text-on-shell'
                          : 'font-medium text-on-shell-muted group-hover/nav:bg-shell-hover group-hover/nav:text-on-shell',
                      )}
                    >
                      {route.nav.label}
                    </span>
                  </Link>
                </li>
              );
            })}
            {layout === 'teacher' ? (
              <li className="flex">
                <Link to="/" className="group/nav relative flex items-center px-0.5 outline-none">
                  <span className="inline-flex h-9 items-center rounded-control px-3 text-ui font-medium text-on-shell-muted transition-[background-color,color] duration-100 group-hover/nav:bg-shell-hover group-hover/nav:text-on-shell group-focus-visible/nav:outline-2 group-focus-visible/nav:outline-offset-2 group-focus-visible/nav:outline-focus-on-shell">
                    Розклад коледжу
                  </span>
                </Link>
              </li>
            ) : null}
          </ul>
        </nav>

        <div className="ms-auto flex min-w-0 items-center gap-1 md:gap-2">
          <SearchTrigger tone="on-shell" className="hidden md:flex md:w-72 xl:w-80" />
          <div className="hidden lg:block">
            <HeaderParity withWeek />
          </div>
          <div className="hidden md:block lg:hidden">
            <HeaderParity withWeek={false} />
          </div>
          <div className="md:hidden">
            <CompactParity />
          </div>
          <ThemeToggle tone="on-shell" className="hidden lg:inline-flex" />
          <div className="ms-1 flex items-center">
            <UserMenu tone="on-shell" />
          </div>
        </div>
      </div>
    </header>
  );
}
