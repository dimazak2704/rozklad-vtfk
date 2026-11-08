import { Send } from 'lucide-react';
import { buttonClass } from '@/components/ui';
import { cn } from '@/lib/cn';
import { QrPlate } from '../QrPlate';
import { TELEGRAM_BOT_HANDLE, TELEGRAM_BOT_URL } from '../telegram';

/** Бот у Telegram: посилання на телефоні, QR-код — щоб відкрити з компʼютера на телефоні. */
export function TelegramCard({ className }: { className?: string }) {
  return (
    <section aria-labelledby="telegram-heading" className={cn('flex gap-4 rounded-panel border border-default bg-surface p-4', className)}>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <h2 id="telegram-heading" className="text-heading font-semibold text-primary">
          Розклад у Telegram
        </h2>
        <p className="text-ui text-secondary">
          Бот {TELEGRAM_BOT_HANDLE} відповідає розкладом на сьогодні й завтра, а групові чати отримують заміни після
          публікації розпорядження.
        </p>
        <a
          href={TELEGRAM_BOT_URL}
          target="_blank"
          rel="noreferrer"
          className={buttonClass({ variant: 'secondary', className: 'mt-1 self-start' })}
        >
          <Send aria-hidden="true" className="size-4" />
          Відкрити бота
        </a>
      </div>
      <QrPlate value={TELEGRAM_BOT_URL} size={88} label={`QR-код: бот ${TELEGRAM_BOT_HANDLE}`} className="hidden self-start p-2 lg:inline-flex" />
    </section>
  );
}
