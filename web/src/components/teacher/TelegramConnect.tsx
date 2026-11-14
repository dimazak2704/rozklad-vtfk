import { CircleCheck, Send } from 'lucide-react';
import { isApiError } from '@/api/errors';
import { useCreateBindingCode, useMyBinding } from '@/api/telegram';
import type { Uuid } from '@/api/types';
import { Button, buttonClass, ErrorState, notify, Skeleton } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { durationLabel } from '@/lib/labels';
import { QrPlate } from '../public/QrPlate';
import { TELEGRAM_BOT_HANDLE } from '../public/telegram';

/** Обліковий запис ще не повʼязаний з викладачем (notification: 422 `teacher-not-linked`). */
function isNotLinked(error: unknown): boolean {
  return isApiError(error) && error.status === 422 && error.code === 'teacher-not-linked';
}

/**
 * Привʼязка Telegram: спершу стан власної привʼязки (`GET /api/notify/bindings/me`); якщо бота ще не підключено —
 * одноразове глибоке посилання на бота з кодом (CONTRACTS §6). З компʼютера — QR-кодом на телефон, з телефона —
 * кнопкою. Після привʼязки бот надсилає запити на заміну з кнопками відповіді.
 */
export function TelegramConnect({ teacherId }: { teacherId: Uuid }) {
  const link = useCreateBindingCode();
  // строк коду — справжній час (сервер не переводить його разом із часом стенда)
  const { realNow: now } = useNow();
  const data = link.data;
  const msLeft = data ? new Date(data.expiresAt).getTime() - now.getTime() : 0;
  const expired = data !== undefined && msLeft <= 0;
  const mine = useMyBinding({ waiting: data !== undefined && !expired });

  const request = () =>
    link.mutate(
      { type: 'TEACHER', teacherId },
      {
        onError: (error) => {
          // 422 teacher-not-linked показуємо в самій картці: це не збій, а стан облікового запису
          if (!isNotLinked(error)) notify.error(error, 'Не вдалося отримати посилання для Telegram');
        },
      },
    );
  const notLinkedError = mine.isError && isNotLinked(mine.error) ? mine.error : link.isError && isNotLinked(link.error) ? link.error : null;
  const binding = mine.data?.bound ? mine.data.binding : null;

  return (
    <section aria-labelledby="telegram-connect-heading" className="flex flex-col gap-3">
      <h2 id="telegram-connect-heading" className="text-title text-primary">
        Telegram
      </h2>
      {mine.isPending ? (
        <Skeleton className="h-5 w-64 max-w-full" />
      ) : notLinkedError ? (
        <p role="alert" className="text-ui text-secondary">
          {notLinkedError.message}
        </p>
      ) : mine.isError ? (
        <ErrorState error={mine.error} onRetry={() => void mine.refetch()} size="sm" title="Не вдалося перевірити привʼязку Telegram" className="py-0" />
      ) : mine.data.bound ? (
        <p role="status" className="flex items-start gap-2 text-ui text-secondary">
          <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-add" />
          <span>
            <span className="font-[650] text-primary">Telegram підключено{binding ? <>: {binding.title}</> : null}.</span> Запити на заміну
            приходять у бот, відповісти можна однією кнопкою.
          </span>
        </p>
      ) : !data || expired ? (
        <div className="flex flex-col items-start gap-3">
          <p className="text-ui text-secondary">
            {expired
              ? 'Посилання прострочене — отримайте нове.'
              : `Підключіть бота ${TELEGRAM_BOT_HANDLE}: запити на заміну приходитимуть у Telegram, а відповісти можна однією кнопкою.`}
          </p>
          <Button
            variant="secondary"
            iconStart={<Send aria-hidden="true" className="size-4" />}
            loading={link.isPending}
            loadingLabel="Створюємо посилання"
            onClick={request}
          >
            {expired ? 'Нове посилання' : 'Підключити Telegram'}
          </Button>
        </div>
      ) : (
        <div className="flex flex-col items-start gap-3" aria-live="polite">
          <p className="text-ui text-secondary">
            Наведіть камеру телефона на код або відкрийте посилання на телефоні. Посилання одноразове, діє ще{' '}
            <span className="font-[650] text-primary figures">{durationLabel(Math.ceil(msLeft / 60_000))}</span>.
          </p>
          <QrPlate value={data.deepLink} size={152} label="QR-код для підключення бота" className="hidden sm:block" />
          <a href={data.deepLink} target="_blank" rel="noreferrer" className={buttonClass({ variant: 'primary' })}>
            <Send aria-hidden="true" className="size-4" />
            Відкрити Telegram
          </a>
        </div>
      )}
    </section>
  );
}
