import { CalendarDays, LayoutDashboard, LogIn, LogOut } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { Link, useLocation } from 'react-router';
import { paths } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { cn } from '@/lib/cn';
import { Avatar } from '../ui/Avatar';
import { buttonClass } from '../ui/styles';

const ITEM =
  'flex cursor-default select-none items-center gap-2.5 rounded-control px-2.5 py-2 text-ui text-primary outline-none data-highlighted:bg-hover';

interface UserMenuProps {
  tone?: 'default' | 'on-shell';
}

/** «Увійти» для гостей; для користувача — меню з кабінетом, панеллю і виходом. */
export function UserMenu({ tone = 'default' }: UserMenuProps) {
  const { status, user, isAdmin, isTeacher, signOut, isDevBypass } = useCurrentUser();
  const location = useLocation();
  const onShell = tone === 'on-shell';

  if (status !== 'authenticated' || !user) {
    return (
      <Link
        to={paths.login(location.pathname === '/' ? undefined : location.pathname + location.search)}
        className={buttonClass({ variant: onShell ? 'shell' : 'secondary', className: 'gap-2' })}
      >
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
          'flex items-center gap-2 rounded-full p-0.5 pe-2 transition-[background-color] duration-100',
          'focus-visible:outline-2 focus-visible:outline-offset-2',
          onShell ? 'hover:bg-shell-hover focus-visible:outline-focus-on-shell' : 'hover:bg-hover focus-visible:outline-focus',
        )}
      >
        <Avatar name={user.name} tone={onShell ? 'on-shell' : 'default'} />
        <span className={cn('hidden max-w-36 truncate text-ui font-semibold lg:inline', onShell ? 'text-on-shell' : 'text-primary')}>
          {user.name}
        </span>
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
          <DropdownMenu.Item className={ITEM} onSelect={() => void signOut()}>
            <LogOut aria-hidden="true" className="size-4 text-muted" />
            Вийти
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
