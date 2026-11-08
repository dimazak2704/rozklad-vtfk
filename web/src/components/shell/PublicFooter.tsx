import { ArrowUpRight } from 'lucide-react';
import { Link } from 'react-router';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { cn } from '@/lib/cn';
import { ThemeSegmented } from './ThemeToggle';

const TELEGRAM_BOT_URL = import.meta.env.VITE_TELEGRAM_BOT_URL || 'https://t.me/scheduler_vtfk_bot';
const COLLEGE_URL = 'https://vtc.vn.ua';

const LINK =
  'inline-flex min-h-8 items-center gap-1 text-ui text-secondary underline-offset-4 hover:text-primary hover:underline ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus md:min-h-0';

/** Останнє посилання залежить від сесії: увійшовши, людина бачить свій кабінет, а не «Вхід». */
function AccountLink() {
  const { status, isAdmin, isTeacher } = useCurrentUser();
  if (status === 'loading') return null;
  if (status === 'authenticated') {
    const to = isTeacher ? '/me' : isAdmin ? '/admin' : null;
    if (!to) return null;
    return (
      <li>
        <Link to={to} className={LINK}>
          {isTeacher ? 'Кабінет викладача' : 'Панель диспетчера'}
        </Link>
      </li>
    );
  }
  return (
    <li>
      <Link to="/login" className={LINK}>
        Вхід для викладачів
      </Link>
    </li>
  );
}

/**
 * Підвал: назва, кілька корисних посилань і вибір теми — єдиний на сайті (у смузі теми немає).
 * На телефоні посилання у дві колонки, а тема — праворуч від назви, щоб підвал не розростався над вкладками.
 * Ціль 32 px на телефоні (більше за мінімум WCAG 2.5.8 у 24 px).
 */
export function PublicFooter({ className }: { className?: string }) {
  return (
    <footer
      className={cn(
        'border-t border-default bg-canvas pb-[calc(var(--tabbar-height)+env(safe-area-inset-bottom))] md:pb-0',
        className,
      )}
    >
      <div
        className={cn(
          'mx-auto grid w-full max-w-[90rem] grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-3 px-4 py-5 sm:px-6',
          'md:flex md:flex-wrap md:gap-x-0 md:gap-y-2 md:py-6 lg:px-8',
        )}
      >
        <span className="text-ui font-semibold text-primary md:order-1">Розклад ВТФК</span>
        {/* на телефоні тема — праворуч від назви; на ширших — в кінці рядка */}
        <ThemeSegmented className="justify-self-end md:order-4" />
        <span className="col-span-2 -mt-2 text-caption text-muted md:order-2 md:mt-0 md:ms-1 md:text-ui md:text-secondary">
          <span aria-hidden="true" className="hidden md:inline">
            ·{' '}
          </span>
          Вінницький технічний фаховий коледж
        </span>
        <nav aria-label="Додаткові посилання" className="col-span-2 md:order-3 md:ms-8 md:me-auto">
          <ul className="grid grid-cols-2 gap-x-6 md:flex md:flex-wrap md:gap-x-5 md:gap-y-2">
            <li>
              <a href={TELEGRAM_BOT_URL} target="_blank" rel="noreferrer" className={LINK}>
                Telegram&#8209;бот
              </a>
            </li>
            <li>
              <Link to="/about" className={LINK}>
                Про систему
              </Link>
            </li>
            <li>
              <a href={COLLEGE_URL} target="_blank" rel="noreferrer" className={LINK}>
                Сайт коледжу
                <ArrowUpRight aria-hidden="true" className="size-3.5" />
                <span className="sr-only">(відкриється в новій вкладці)</span>
              </a>
            </li>
            <AccountLink />
          </ul>
        </nav>
      </div>
    </footer>
  );
}
