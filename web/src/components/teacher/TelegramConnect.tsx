import { Send } from 'lucide-react';
import { useCreateBindingCode } from '@/api/telegram';
import type { Uuid } from '@/api/types';
import { Button, buttonClass, notify } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { durationLabel } from '@/lib/labels';
import { QrPlate } from '../public/QrPlate';
import { TELEGRAM_BOT_HANDLE } from '../public/telegram';

/**
 * Привʼязка Telegram: одноразове глибоке посилання на бота з кодом (CONTRACTS §6). З компʼютера — QR-кодом
 * на телефон, з телефона — кнопкою. Після привʼязки бот надсилає запити на заміну з кнопками відповіді.
 */
export function TelegramConnect({ teacherId }: { teacherId: Uuid }) {
  const link = useCreateBindingCode();
  const { now } = useNow();
  const data = link.data;
  const msLeft = data ? new Date(data.expiresAt).getTime() - now.getTime() : 0;
  const expired = data !== undefined && msLeft <= 0;

  const request = () => link.mutate({ type: 'TEACHER', teacherId }, { onError: (error) => notify.error(error, 'Не вдалося отримати посилання для Telegram') });

  return (
    <section aria-labelledby="telegram-connect-heading" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
      <h2 id="telegram-connect-heading" className="text-heading font-semibold text-primary">
        Telegram
      </h2>
      <p className="text-ui text-secondary">
        Підключіть бота {TELEGRAM_BOT_HANDLE}: запити на заміну приходитимуть у Telegram, а відповісти можна однією кнопкою.
      </p>
      {!data || expired ? (
        <div className="flex flex-col gap-2">
          {expired ? <p className="text-caption text-muted">Посилання прострочене — отримайте нове.</p> : null}
          <Button
            variant="secondary"
            iconStart={<Send aria-hidden="true" className="size-4" />}
            loading={link.isPending}
            loadingLabel="Створюємо посилання"
            onClick={request}
            className="self-start"
          >
            {expired ? 'Нове посилання' : 'Підключити Telegram'}
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-3" aria-live="polite">
          <QrPlate value={data.deepLink} size={152} label="QR-код для підключення бота" className="self-start" />
          <p className="text-caption text-secondary">
            Наведіть камеру телефона на код або відкрийте посилання на телефоні. Посилання одноразове, діє ще{' '}
            <span className="font-semibold text-primary figures">{durationLabel(Math.ceil(msLeft / 60_000))}</span>.
          </p>
          <a href={data.deepLink} target="_blank" rel="noreferrer" className={buttonClass({ variant: 'primary', className: 'self-start' })}>
            <Send aria-hidden="true" className="size-4" />
            Відкрити Telegram
          </a>
        </div>
      )}
    </section>
  );
}
