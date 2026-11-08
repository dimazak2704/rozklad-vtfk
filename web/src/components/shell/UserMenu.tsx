import { CalendarDays, LayoutDashboard, LogIn, LogOut } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { Link, useLocation } from 'react-router';
import { paths } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { cn } from '@/lib/cn';
import { Avatar } from '../ui/Avatar';
import { buttonClass } from '../ui/styles';
import { ThemeSegmented } from './ThemeToggle';

const ITEM =
  'flex h-9 cursor-default select-none items-center gap-2.5 rounded-control px-2.5 text-ui text-primary outline-none data-highlighted:bg-hover pointer-coarse:h-11';

interface UserMenuProps {
  tone?: 'default' | 'on-shell';
}

/**
 * Гостям — тихе посилання входу («Для викладачів» у смузі на десктопі, «Увійти» на телефоні);
 * користувачу — меню з кабінетом, панеллю, темою (у робочих панелях) і виходом.
 */
export function UserMenu({ tone = 'default' }: UserMenuProps) {
  const { status, user, isAdmin, isTeacher, signOut, isDevBypass } = useCurrentUser();
  const location = useLocation();
  const onShell = tone === 'on-shell';

  if (status !== 'authenticated' || !user) {
    const to = paths.login(location.pathname === '/' ? undefined : location.pathname + location.search);
    if (onShell) {
      return (
        <Link
          to={to}
          className={cn(
            'inline-flex h-9 items-center rounded-control px-2 text-ui font-medium text-on-shell-muted whitespace-nowrap',
            'transition-[background-color,color] duration-100 hover:bg-shell-hover hover:text-on-shell',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-on-shell',
          )}
        >
          <span className="md:hidden">Увійти</span>
          <span className="hidden md:inline">Для викладачів</span>
        </Link>
      );
    }
    return (
      <Link to={to} className={buttonClass({ variant: 'secondary', className: 'gap-2' })}>
        <LogIn aria-hidden="true" className="size-4" />
        <span>Увійти</span>
      </Link>
    );
  }

  const roleLabel = isAdmin ? 'Диспетчер' : isTeacher ? 'Викладач' : 'Користувач';

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        aria-label={`Обліковий запис: ${user.name}`}
        className={cn(
          'flex items-center rounded-full transition-[background-color] duration-100',
          'focus-visible:outline-2 focus-visible:outline-offset-2',
          // у смузі — лише аватар 32 px, але ціль натискання 44 px
          onShell
            ? '-m-1.5 size-11 justify-center hover:bg-shell-hover focus-visible:outline-focus-on-shell'
            : 'gap-2 p-1 pe-2.5 hover:bg-hover focus-visible:outline-focus',
        )}
      >
        <Avatar name={user.name} tone={onShell ? 'on-shell' : 'default'} className={onShell ? undefined : 'size-7'} />
        {!onShell ? <span className="hidden max-w-40 truncate text-ui font-medium text-primary lg:inline">{user.name}</span> : null}
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="anim-pop z-50 min-w-56 rounded-panel border border-default bg-raised p-1 text-primary shadow-popover"
        >
          <div className="flex items-center gap-2.5 px-2.5 py-2">
            <Avatar name={user.name} size="lg" />
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-ui font-semibold">{user.name}</span>
              <span className="truncate text-caption text-muted">
                {roleLabel}
                {user.username ? ` · ${user.username}` : ''}
                {isDevBypass ? ' · dev' : ''}
              </span>
            </div>
          </div>
          <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" />
          {isTeacher || isAdmin ? (
            <DropdownMenu.Item asChild className={ITEM}>
              <Link to="/me">
                <CalendarDays aria-hidden="true" className="size-4 text-muted" />
                Мій розклад
              </Link>
            </DropdownMenu.Item>
          ) : null}
          {isAdmin ? (
            <DropdownMenu.Item asChild className={ITEM}>
              <Link to="/admin">
                <LayoutDashboard aria-hidden="true" className="size-4 text-muted" />
                Панель диспетчера
              </Link>
            </DropdownMenu.Item>
          ) : null}
          <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" />
          {!onShell ? (
            <>
              <DropdownMenu.Label className="px-2.5 pt-1.5 pb-1 text-meta font-semibold text-muted">Тема</DropdownMenu.Label>
              {/* стрілки й літери керують перемикачем, а не меню */}
              <div className="px-2.5 pb-2" onKeyDown={(event) => event.stopPropagation()}>
                <ThemeSegmented />
              </div>
              <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" />
            </>
          ) : null}
          <DropdownMenu.Item className={ITEM} onSelect={() => void signOut()}>
            <LogOut aria-hidden="true" className="size-4 text-muted" />
            Вийти
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
