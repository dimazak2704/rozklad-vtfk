import { CircleCheck, Send, Unlink } from 'lucide-react';
import { isApiError } from '@/api/errors';
import { useCreateBindingCode, useMyBinding, useUnlinkMyBinding } from '@/api/telegram';
import type { ChatBindingDto, Uuid } from '@/api/types';
import { Button, buttonClass, ErrorState, notify, Skeleton } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { durationLabel } from '@/lib/labels';
import { confirm } from '@/stores/confirm';
import { QrPlate } from '../public/QrPlate';
import { TELEGRAM_BOT_HANDLE } from '../public/telegram';

/** Обліковий запис ще не повʼязаний з викладачем (notification: 422 `teacher-not-linked`). */
function isNotLinked(error: unknown): boolean {
  return isApiError(error) && error.status === 422 && error.code === 'teacher-not-linked';
}

/**
 * Привʼязаний Telegram зі змогою відвʼязати його (сторінка «Сповіщення»): чий акаунт і який чат, щоб людина
 * впізнала свій телефон, і «Відвʼязати» з підтвердженням. Після відвʼязки картка знову пропонує підключити бота.
 */
function LinkedTelegram({ binding, onUnlinked }: { binding: ChatBindingDto | null; onUnlinked: () => void }) {
  const unlink = useUnlinkMyBinding();
  const onUnlink = async () => {
    const ok = await confirm({
      title: 'Відвʼязати Telegram?',
      description: 'Бот більше не надсилатиме вам запити на заміни в Telegram. Листи на пошту лишаться.',
      confirmLabel: 'Відвʼязати',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    unlink.mutate(undefined, {
      onSuccess: () => {
        onUnlinked();
        notify.success('Telegram відвʼязано', { description: 'Підключити знову можна тут же.' });
      },
      onError: (error) => notify.error(error, 'Не вдалося відвʼязати Telegram'),
    });
  };
  // акаунт, що погасив код («Микола (@krentsin)»); у старих привʼязках його немає — тоді лише чат
  const account = binding?.telegramAccount ?? null;
  return (
    <div className="flex flex-col items-start gap-3">
      <div role="status" className="flex items-start gap-2 text-ui text-secondary">
        <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-add" />
        <div className="flex min-w-0 flex-col gap-1">
          <p className="font-[650] break-words text-primary">
            Telegram привʼязано{account ? `: ${account}` : binding ? `: чат ${binding.chatId}` : ''}
          </p>
          {account && binding ? (
            <p className="text-caption text-muted">
              Чат <span className="figures">{binding.chatId}</span>
            </p>
          ) : null}
          <p>Запити на заміну приходять у бот, відповісти можна однією кнопкою.</p>
        </div>
      </div>
      <Button
        variant="secondary"
        iconStart={<Unlink aria-hidden="true" className="size-4" />}
        loading={unlink.isPending}
        loadingLabel="Відвʼязуємо Telegram"
        onClick={() => void onUnlink()}
      >
        Відвʼязати
      </Button>
    </div>
  );
}

/**
 * Привʼязка Telegram: спершу стан власної привʼязки (`GET /api/notify/bindings/me`); якщо бота ще не підключено —
 * одноразове глибоке посилання на бота з кодом (CONTRACTS §6). З компʼютера — QR-кодом на телефон, з телефона —
 * кнопкою. Після привʼязки бот надсилає запити на заміну з кнопками відповіді. `allowUnlink` — показати, чий
 * Telegram привʼязано, і дати його відвʼязати (сторінка «Сповіщення»); на «Мій розклад» лише стан.
 */
export function TelegramConnect({ teacherId, allowUnlink = false }: { teacherId: Uuid; allowUnlink?: boolean }) {
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
      ) : mine.data.bound && allowUnlink ? (
        // старе одноразове посилання вже погашене: після відвʼязки картка має запропонувати нове, а не його
        <LinkedTelegram binding={binding} onUnlinked={() => link.reset()} />
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
