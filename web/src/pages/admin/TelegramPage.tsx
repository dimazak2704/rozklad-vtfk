import { ExternalLink, KeyRound, Send } from 'lucide-react';
import { useState } from 'react';
import { useLocation, useSearchParams } from 'react-router';
import { useBindings, useBotStatus, useSendTestMessage } from '@/api/telegram';
import { BindingCodeDialog } from '@/components/admin-daily/telegram/BindingCodeDialog';
import { BindingsTable } from '@/components/admin-daily/telegram/BindingsTable';
import { DeliveriesTable } from '@/components/admin-daily/telegram/DeliveriesTable';
import { Button, notify, PageHeader, Panel, Skeleton, StatusDot, Tabs, TabsContent, TabsList, TabsTrigger, type MoreMenuItem } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useRegisterCommands } from '@/stores/commands';
import { trackFinal } from '@/lib/undo/actions';

type Section = 'chats' | 'log';

/**
 * Telegram: привʼязані чати груп, викладачів і диспетчерів, коди привʼязки з QR, журнал розсилки.
 * Стан бота — одним рядком під заголовком (на звʼязку чи ні); відкрити бота й тестове повідомлення —
 * у «⋯». Чати й журнал — вкладками однієї панелі, без окремої картки «Бот» з лічильниками.
 */
export default function TelegramPage() {
  const bindings = useBindings();
  const bot = useBotStatus();
  const test = useSendTestMessage();
  const [codeOpen, setCodeOpen] = useState(false);
  const [params, setParams] = useSearchParams();
  // старі посилання «/admin/telegram#deliveries» (з розпорядження) відкривають журнал
  const { hash } = useLocation();
  const section: Section = params.get('tab') === 'log' || hash === '#deliveries' ? 'log' : 'chats';

  useRegisterCommands([
    { id: 'binding-code', label: 'Створити код привʼязки Telegram', group: 'Telegram', icon: KeyRound, shortcut: 'n', perform: () => setCodeOpen(true) },
  ]);
  useHotkeys([{ keys: 'n', handler: () => setCodeOpen(true), description: 'Новий код привʼязки', group: 'Telegram' }]);

  const sendTest = async () => {
    try {
      const delivery = await test.mutateAsync();
      if (delivery.status === 'SENT') {
        notify.success('Тестове повідомлення надіслано', { description: 'Перевірте чат власника бота в Telegram.' });
        trackFinal('Надіслано тестове повідомлення в Telegram', 'Повідомлення в Telegram не відкликається');
      } else {
        // RETRYING: Telegram тимчасово недоступний, сервіс повторить сам — це не успіх, але й не відмова
        notify.info('Тестове повідомлення в черзі', {
          description: delivery.error ? `Telegram поки не відповів: ${delivery.error}` : 'Сервіс повторить спробу автоматично.',
        });
      }
    } catch (error) {
      notify.error(error, 'Не вдалося надіслати тестове повідомлення');
    }
  };

  const more: MoreMenuItem[] = [
    ...(bot.data ? [{ label: 'Відкрити бота', icon: <ExternalLink aria-hidden="true" className="size-4" />, href: bot.data.link }] : []),
    { label: 'Тестове повідомлення', icon: <Send aria-hidden="true" className="size-4" />, onSelect: () => void sendTest(), disabled: test.isPending },
  ];

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Telegram"
        meta={<BotLine status={bot.data} loading={bot.isPending} failed={bot.isError} />}
        primaryAction={
          <Button variant="primary" onClick={() => setCodeOpen(true)} iconStart={<KeyRound aria-hidden="true" className="size-4" />}>
            Код привʼязки
          </Button>
        }
        more={more}
      />

      <Tabs
        value={section}
        onValueChange={(value) =>
          setParams(
            (prev) => {
              const next = new URLSearchParams(prev);
              if (value === 'log') next.set('tab', 'log');
              else next.delete('tab');
              return next;
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Розділи Telegram">
          <TabsTrigger value="chats">Привʼязані чати</TabsTrigger>
          <TabsTrigger value="log">Журнал розсилки</TabsTrigger>
        </TabsList>
        <TabsContent value="chats">
          <Panel>
            <BindingsTable bindings={bindings.data} loading={bindings.isPending} error={bindings.error} onRetry={() => void bindings.refetch()} />
          </Panel>
        </TabsContent>
        <TabsContent value="log">
          <Panel id="deliveries">
            <DeliveriesTable />
          </Panel>
        </TabsContent>
      </Tabs>

      <BindingCodeDialog open={codeOpen} onOpenChange={setCodeOpen} />
    </div>
  );
}

/** «@scheduler_vtfk_bot · на звʼязку» — словом і крапкою; причина збою — лише коли бот не відповідає. */
function BotLine({
  status,
  loading,
  failed,
}: {
  status: ReturnType<typeof useBotStatus>['data'];
  loading: boolean;
  failed: boolean;
}) {
  if (loading) return <Skeleton className="h-5 w-56" />;
  if (failed || !status) return <span className="text-danger">Стан бота не завантажено.</span>;
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <span className="font-[650] text-primary">@{status.username}</span>
      {status.connected ? (
        <StatusDot tone="live" label="На звʼязку" showLabel />
      ) : (
        <>
          <StatusDot tone="cancel" label="Не на звʼязку" showLabel />
          <span className="text-danger">{status.problem ?? 'Перевірте токен і мережу сервісу notification.'}</span>
        </>
      )}
    </span>
  );
}
