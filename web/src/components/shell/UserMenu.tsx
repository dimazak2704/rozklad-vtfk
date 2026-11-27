import { CalendarDays, ChevronsUpDown, LayoutDashboard, LogOut } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { Link, useLocation } from 'react-router';
import { paths } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { cn } from '@/lib/cn';
import { Avatar } from '../ui/Avatar';
import { Tooltip, TruncatedText } from '../ui/Tooltip';
import { ThemeRadioItems } from './ThemeToggle';

const ITEM =
  'flex min-h-9 cursor-default select-none items-center gap-2.5 rounded-control px-2.5 text-ui text-primary outline-none data-highlighted:bg-hover pointer-coarse:min-h-11';

interface UserMenuProps {
  /** `avatar` — коло 32 px з ціллю 44 px (шапки); `block` — аватар, імʼя й роль рядком (низ бічної панелі). */
  variant?: 'avatar' | 'block';
  /** Застаріле: колишня синя смуга. */
  tone?: 'default' | 'on-shell';
}

/**
 * Гостям — тихе текстове посилання «Увійти»; користувачу — меню з кабінетом, панеллю, темою й виходом.
 * Тема в меню однакова в кабінеті викладача й панелі диспетчера (у шапці теми немає).
 */
export function UserMenu({ variant = 'avatar' }: UserMenuProps) {
  const { status, user, isAdmin, isTeacher, signOut, isDevBypass } = useCurrentUser();
  const location = useLocation();
  const { pathname } = location;

  if (status !== 'authenticated' || !user) {
    const to = paths.login(pathname === '/' ? undefined : pathname + location.search);
    return (
      <Link
        to={to}
        className={cn(
          // та сама нейтральна риска наведення, що й у розділів шапки (interaction.css, `.nav-link`)
          'nav-link relative inline-flex h-10 items-center rounded-control px-3 text-ui font-[650] whitespace-nowrap text-primary',
          'active:bg-active pointer-coarse:h-11',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        )}
      >
        Увійти
      </Link>
    );
  }

  const roleLabel = isAdmin ? 'Диспетчер' : isTeacher ? 'Викладач' : 'Користувач';
  const inCabinet = pathname.startsWith('/me');
  const inAdmin = pathname.startsWith('/admin');

  return (
    <DropdownMenu.Root>
      {/* у шапці — лише аватар: підказка називає меню, як у сусідніх кнопок пошуку й теми; у бічній панелі імʼя видно */}
      <Tooltip content={`Обліковий запис: ${user.name}`} side="bottom" align="end" disabled={variant === 'block'}>
        <DropdownMenu.Trigger
          aria-label={`Обліковий запис: ${user.name}`}
          className={cn(
            'flex items-center rounded-control transition-[background-color] duration-150',
            'hover:bg-hover active:bg-active aria-expanded:bg-hover',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
            variant === 'block' ? 'h-11 w-full gap-2.5 px-2 text-start' : '-m-1 size-11 justify-center rounded-full',
          )}
        >
          <Avatar name={user.name} />
          {variant === 'block' ? (
            <>
              <span className="flex min-w-0 flex-1 flex-col">
                {/* довге імʼя в рейці 232 px обрізається — повне в підказці, лише коли справді обрізане */}
                <TruncatedText className="truncate text-ui font-[650] text-primary">{user.name}</TruncatedText>
                <span className="truncate text-caption text-muted">{roleLabel}</span>
              </span>
              <ChevronsUpDown aria-hidden="true" className="size-4 text-muted" />
            </>
          ) : null}
        </DropdownMenu.Trigger>
      </Tooltip>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align={variant === 'block' ? 'start' : 'end'}
          side={variant === 'block' ? 'top' : 'bottom'}
          sideOffset={6}
          collisionPadding={8}
          className="anim-pop z-50 min-w-60 rounded-popover border border-default bg-raised p-1.5 text-primary shadow-popover"
        >
          <div className="flex items-center gap-2.5 px-2.5 py-2">
            <Avatar name={user.name} size="lg" />
            <div className="flex min-w-0 flex-col">
              <TruncatedText className="truncate text-ui font-[650]">{user.name}</TruncatedText>
              <span className="truncate text-caption text-muted">
                {roleLabel}
                {user.username ? ` · ${user.username}` : ''}
                {isDevBypass ? ' · dev' : ''}
              </span>
            </div>
          </div>
          {(isTeacher || isAdmin) && !inCabinet ? (
            <>
              <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" />
              <DropdownMenu.Item asChild className={ITEM}>
                <Link to="/me">
                  <CalendarDays aria-hidden="true" className="size-4 text-muted" />
                  Мій розклад
                </Link>
              </DropdownMenu.Item>
            </>
          ) : null}
          {isAdmin && !inAdmin ? (
            <>
              {inCabinet ? <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" /> : null}
              <DropdownMenu.Item asChild className={ITEM}>
                <Link to="/admin">
                  <LayoutDashboard aria-hidden="true" className="size-4 text-muted" />
                  Панель диспетчера
                </Link>
              </DropdownMenu.Item>
            </>
          ) : null}
          <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" />
          <DropdownMenu.Label className="px-2.5 pt-1.5 pb-1 text-caption font-[650] text-muted">Тема</DropdownMenu.Label>
          <ThemeRadioItems />
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
