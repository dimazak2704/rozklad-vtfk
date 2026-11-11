import { ArrowUpRight } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { cn } from '@/lib/cn';
import { ThemeMenu } from './ThemeToggle';

const TELEGRAM_BOT_URL = import.meta.env.VITE_TELEGRAM_BOT_URL || 'https://t.me/scheduler_vtfk_bot';
const COLLEGE_URL = 'https://vtc.vn.ua';

const LINK = cn(
  'inline-flex min-h-11 items-center gap-1 rounded-control text-ui text-secondary transition-colors duration-150',
  'hover:text-primary sm:min-h-8 pointer-coarse:min-h-11',
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
);

/**
 * Увійшовши, людина бачить шлях у свій кабінет. Гостям посилання на вхід тут немає: «Увійти» вже є в шапці.
 * Усередині кабінету чи панелі посилання на них самих теж немає.
 */
function AccountLink() {
  const { status, isAdmin, isTeacher } = useCurrentUser();
  const { pathname } = useLocation();
  if (status !== 'authenticated') return null;
  const to = isTeacher ? '/me' : isAdmin ? '/admin' : null;
  if (!to || pathname === to || pathname.startsWith(`${to}/`)) return null;
  return (
    <li>
      <Link to={to} className={LINK}>
        {isTeacher ? 'Кабінет викладача' : 'Панель диспетчера'}
      </Link>
    </li>
  );
}

/**
 * Підвал: назва з коледжем, один рядок тихих посилань і «Тема: як у системі ▾» праворуч.
 * Усі рядки мають спільний лівий край. Над нижньою панеллю вкладок лишається місце.
 */
export function PublicFooter({ className }: { className?: string }) {
  return (
    <footer className={cn('pb-[calc(var(--tabbar-height)+env(safe-area-inset-bottom))] lg:pb-0', className)}>
      <div className="mx-auto w-full max-w-[calc(76rem+2*var(--page-gutter))] px-(--page-gutter)">
        <div className="flex flex-col gap-3 border-t border-separator py-6 lg:flex-row lg:items-center lg:gap-8">
          <p className="text-caption text-muted">
            <span className="block font-[650] text-secondary sm:inline">Розклад ВТФК</span>
            <span aria-hidden="true" className="hidden sm:inline">
              {' · '}
            </span>
            Вінницький технічний фаховий коледж
          </p>
          <nav aria-label="Додаткові посилання" className="lg:me-auto">
            {/* на телефоні — стовпчик рядків по 44 px, а не рвані два-плюс-два */}
            <ul className="flex flex-col sm:flex-row sm:flex-wrap sm:gap-x-5 sm:gap-y-1">
              <li>
                <a href={TELEGRAM_BOT_URL} target="_blank" rel="noreferrer" className={LINK}>
                  Telegram&#8209;бот
                  <span className="sr-only">(відкриється в новій вкладці)</span>
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
          <ThemeMenu className="-ms-1.5 w-fit lg:ms-0" />
        </div>
      </div>
    </footer>
  );
}
