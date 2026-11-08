import { ExternalLink, Send } from 'lucide-react';
import { useBotStatus, useSendTestMessage } from '@/api/telegram';
import type { ChatBindingDto } from '@/api/types';
import { Button, ErrorState, notify, Panel, SkeletonText, StatusDot } from '@/components/ui';

const MODE_TEXT = {
  POLLING: 'Long polling: бот тримає зʼєднання з Telegram і сам забирає нові повідомлення',
  WEBHOOK: 'Webhook: Telegram надсилає оновлення на сервер коледжу',
} as const;

/** Стан бота @scheduler_vtfk_bot: на звʼязку чи ні, режим, скільки чатів привʼязано, тестове повідомлення. */
export function BotStatusPanel({ bindings }: { bindings: readonly ChatBindingDto[] | undefined }) {
  const bot = useBotStatus();
  const test = useSendTestMessage();

  const sendTest = async () => {
    try {
      await test.mutateAsync();
      notify.success('Тестове повідомлення надіслано', { description: 'Перевірте чат власника бота в Telegram.' });
    } catch (error) {
      notify.error(error, 'Не вдалося надіслати тестове повідомлення');
    }
  };

  const count = (type: ChatBindingDto['type']) => bindings?.filter((b) => b.type === type).length ?? 0;

  return (
    <Panel title="Бот" headingLevel={2}>
      {bot.isPending ? (
        <SkeletonText lines={3} />
      ) : bot.isError ? (
        <ErrorState error={bot.error} onRetry={() => void bot.refetch()} size="sm" align="start" />
      ) : (
        <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-start">
          <div className="flex min-w-0 flex-col gap-2">
            <p className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-condensed text-title font-bold text-primary">@{bot.data.username}</span>
              {bot.data.connected ? (
                <StatusDot tone="live" pulse label="На звʼязку" showLabel />
              ) : (
                <StatusDot tone="cancel" label="Не підключений — перевірте токен і мережу сервісу notification" showLabel />
              )}
            </p>
            <p className="text-caption text-secondary">{MODE_TEXT[bot.data.mode]}</p>
            <dl className="mt-1 flex flex-wrap gap-x-6 gap-y-1 text-ui">
              <div className="flex items-baseline gap-1.5">
                <dt className="text-secondary">Чати груп</dt>
                <dd className="font-semibold text-primary figures">{count('GROUP_CHAT')}</dd>
              </div>
              <div className="flex items-baseline gap-1.5">
                <dt className="text-secondary">Викладачі</dt>
                <dd className="font-semibold text-primary figures">{count('TEACHER')}</dd>
              </div>
              <div className="flex items-baseline gap-1.5">
                <dt className="text-secondary">Диспетчерська</dt>
                <dd className="font-semibold text-primary figures">{count('ADMIN_CHAT')}</dd>
              </div>
            </dl>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button asChild variant="secondary">
              <a href={bot.data.link} target="_blank" rel="noopener noreferrer">
                <ExternalLink aria-hidden="true" className="size-4" />
                Відкрити бота
              </a>
            </Button>
            <Button variant="secondary" loading={test.isPending} onClick={() => void sendTest()} iconStart={<Send aria-hidden="true" className="size-4" />}>
              Тестове повідомлення
            </Button>
          </div>
        </div>
      )}
    </Panel>
  );
}
