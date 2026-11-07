import { Link } from 'react-router';

const TELEGRAM_BOT_URL = import.meta.env.VITE_TELEGRAM_BOT_URL || 'https://t.me/scheduler_vtfk_bot';

/** Підвал: назва коледжу, корисні посилання, бот. */
export function PublicFooter() {
  return (
    <footer className="border-t border-default bg-surface pb-[calc(var(--tabbar-height)+env(safe-area-inset-bottom))] md:pb-0">
      <div className="mx-auto flex w-full max-w-[90rem] flex-col gap-4 px-4 py-6 sm:px-6 md:flex-row md:items-start md:justify-between lg:px-8">
        <div className="flex flex-col gap-1">
          <p className="font-condensed text-heading font-bold text-primary">Розклад ВТФК</p>
          <p className="text-caption text-secondary">Вінницький технічний фаховий коледж</p>
        </div>
        <nav aria-label="Додаткові посилання">
          <ul className="flex flex-wrap gap-x-5 gap-y-2 text-ui">
            <li>
              <Link to="/bells" className="text-secondary underline-offset-4 hover:text-primary hover:underline">
                Дзвінки
              </Link>
            </li>
            <li>
              <Link to="/about" className="text-secondary underline-offset-4 hover:text-primary hover:underline">
                Про систему
              </Link>
            </li>
            <li>
              <a
                href={TELEGRAM_BOT_URL}
                target="_blank"
                rel="noreferrer"
                className="text-secondary underline-offset-4 hover:text-primary hover:underline"
              >
                Telegram-бот @scheduler_vtfk_bot
              </a>
            </li>
            <li>
              <Link to="/login" className="text-secondary underline-offset-4 hover:text-primary hover:underline">
                Вхід для викладачів
              </Link>
            </li>
          </ul>
        </nav>
      </div>
    </footer>
  );
}
