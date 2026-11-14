import { Search } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { navFor, type LayoutKind } from '@/app/routes';
import { useScrolled } from '@/hooks/useScrolled';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';
import { IconButton } from '../ui/IconButton';
import { activeNavId } from './activeSection';
import { SearchTrigger } from './SearchTrigger';
import { StandTimeBadge } from './StandClock';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';

const LINK = cn(
  'nav-link relative inline-flex h-10 items-center rounded-control px-3 text-ui whitespace-nowrap transition-colors duration-150',
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
);

/**
 * Текстове посилання шапки: активне — чорнилом з рискою 2 px акценту на 6 px нижче тексту. Мишею під
 * пунктом проїжджає та сама риска, але нейтральна (interaction.css, `.nav-link`); фокус показує її одразу.
 */
function HeaderLink({ to, label, active }: { to: string; label: string; active: boolean }) {
  return (
    <Link
      to={to}
      aria-current={active ? 'page' : undefined}
      className={cn(LINK, active ? 'font-[650] text-primary' : 'font-[550] text-secondary hover-fine:text-primary focus-visible:text-primary')}
    >
      {/* невидимий жирний дублікат тримає ширину, щоб сусіди не «стрибали» при переході */}
      <span className="inline-grid">
        <span className="col-start-1 row-start-1">{label}</span>
        <span aria-hidden="true" className="invisible col-start-1 row-start-1 font-[650]">
          {label}
        </span>
      </span>
    </Link>
  );
}

/**
 * Шапка публічного сайту й кабінету — на полотні, без смуги: знак і назва, три розділи текстом, тихе поле
 * пошуку (на головній його немає — там велике поле) і «Увійти» чи аватар. Лінія знизу зʼявляється лише
 * після прокрутки. Вужче за 1024 px — знак, назва, пошук іконкою; розділи — у нижній панелі.
 * Теми й парності в шапці немає: тема — у підвалі й меню користувача, парність — один раз на сторінці.
 */
export function PublicHeader({ layout = 'public' }: { layout?: Extract<LayoutKind, 'public' | 'teacher'> }) {
  const nav = navFor(layout).filter((route) => route.nav.header);
  const { pathname } = useLocation();
  const activeId = activeNavId(nav, pathname);
  const scrolled = useScrolled();
  const openSearch = useCommandStore((state) => state.setOpen);
  const onHome = layout === 'public' && pathname === '/';

  return (
    <header
      className={cn(
        'sticky top-0 z-30 border-b bg-canvas transition-[border-color] duration-150',
        scrolled ? 'border-separator' : 'border-transparent',
      )}
    >
      <div className="mx-auto flex h-(--header-height) w-full max-w-[calc(76rem+2*var(--page-gutter))] items-center gap-2 px-(--page-gutter)">
        <Wordmark to={layout === 'teacher' ? '/me' : '/'} className="-ms-1 px-1" />
        <nav aria-label={layout === 'teacher' ? 'Кабінет викладача' : 'Розділи сайту'} className="ms-6 hidden lg:block">
          <ul className="flex items-center gap-1">
            {nav.map((route) => (
              <li key={route.id}>
                <HeaderLink to={route.path} label={route.nav.label} active={route.id === activeId} />
              </li>
            ))}
            {layout === 'teacher' ? (
              <li>
                <HeaderLink to="/" label="Розклад коледжу" active={false} />
              </li>
            ) : null}
          </ul>
        </nav>

        <div className="ms-auto flex min-w-0 items-center gap-2">
          <StandTimeBadge />
          {onHome ? null : <SearchTrigger className="hidden w-64 lg:flex xl:w-72" />}
          <IconButton
            label="Пошук розкладу"
            shortcut="mod+k"
            icon={<Search aria-hidden="true" className="size-5" />}
            size="lg"
            className="lg:hidden"
            onClick={() => openSearch(true)}
          />
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
