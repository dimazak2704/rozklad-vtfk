import { KeyRound } from 'lucide-react';
import { useState } from 'react';
import { useBindings } from '@/api/telegram';
import { BindingCodeDialog } from '@/components/admin-daily/telegram/BindingCodeDialog';
import { BindingsTable } from '@/components/admin-daily/telegram/BindingsTable';
import { BotStatusPanel } from '@/components/admin-daily/telegram/BotStatusPanel';
import { DeliveriesTable } from '@/components/admin-daily/telegram/DeliveriesTable';
import { Button, PageHeader, Panel } from '@/components/ui';
import { useRegisterCommands } from '@/stores/commands';

/**
 * Telegram: стан бота, привʼязані чати груп, викладачів і диспетчерів, коди привʼязки з QR, журнал розсилки.
 */
export default function TelegramPage() {
  const bindings = useBindings();
  const [codeOpen, setCodeOpen] = useState(false);

  useRegisterCommands([
    { id: 'binding-code', label: 'Створити код привʼязки Telegram', group: 'Telegram', icon: KeyRound, perform: () => setCodeOpen(true) },
  ]);

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Telegram"
        description="Бот надсилає групам розпорядження, а викладачам — запити на заміни. Тут видно, хто підключений і що дійшло."
        actions={
          <Button variant="primary" onClick={() => setCodeOpen(true)} iconStart={<KeyRound aria-hidden="true" className="size-4" />}>
            Код привʼязки
          </Button>
        }
      />

      <BotStatusPanel bindings={bindings.data} />

      <Panel title="Привʼязані чати" description="Чат групи отримує лише свої рядки розпорядження; викладач — особисті запити на заміни.">
        <BindingsTable bindings={bindings.data} loading={bindings.isPending} error={bindings.error} onRetry={() => void bindings.refetch()} />
      </Panel>

      <Panel id="deliveries" title="Журнал розсилки" description="Останні повідомлення бота: розпорядження, запити, службові.">
        <DeliveriesTable />
      </Panel>

      <BindingCodeDialog open={codeOpen} onOpenChange={setCodeOpen} />
    </div>
  );
}
