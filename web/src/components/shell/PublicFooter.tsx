import { ArrowUpRight } from 'lucide-react';
import { Link } from 'react-router';
import { cn } from '@/lib/cn';
import { ThemeSegmented } from './ThemeToggle';

const TELEGRAM_BOT_URL = import.meta.env.VITE_TELEGRAM_BOT_URL || 'https://t.me/scheduler_vtfk_bot';
const COLLEGE_URL = 'https://vtc.vn.ua';

const LINK =
  'inline-flex min-h-8 items-center gap-1 text-ui text-secondary underline-offset-4 hover:text-primary hover:underline ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus md:min-h-0';

/**
 * Підвал: назва, кілька корисних посилань і вибір теми — єдиний на сайті (у смузі теми немає).
 * Посилання — рядком із переносом; ціль 32 px на телефоні (більше за мінімум WCAG 2.5.8 у 24 px).
 */
export function PublicFooter({ className }: { className?: string }) {
  return (
    <footer
      className={cn(
        'border-t border-default bg-canvas pb-[calc(var(--tabbar-height)+env(safe-area-inset-bottom))] md:pb-0',
        className,
      )}
    >
      <div className="mx-auto flex w-full max-w-[90rem] flex-col gap-3 px-4 py-5 sm:px-6 md:flex-row md:flex-wrap md:items-center md:gap-x-8 md:py-6 lg:px-8">
        <p className="flex flex-col md:flex-row md:gap-1">
          <span className="text-ui font-semibold text-primary">Розклад ВТФК</span>
          <span className="text-caption text-muted md:text-ui md:text-secondary">
            <span aria-hidden="true" className="hidden md:inline">
              ·{' '}
            </span>
            Вінницький технічний фаховий коледж
          </span>
        </p>
        <nav aria-label="Додаткові посилання" className="md:me-auto">
          <ul className="flex flex-wrap gap-x-5 gap-y-1 md:gap-y-2">
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
            <li>
              <Link to="/login" className={LINK}>
                Вхід для викладачів
              </Link>
            </li>
          </ul>
        </nav>
        <ThemeSegmented className="mt-1 md:mt-0" />
      </div>
    </footer>
  );
}
