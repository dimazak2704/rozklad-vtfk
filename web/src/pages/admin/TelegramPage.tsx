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
 * Пояснення, що бот надсилає й кому, — у самому діалозі коду привʼязки, а не абзацами над таблицями.
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
        primaryAction={
          <Button variant="primary" onClick={() => setCodeOpen(true)} iconStart={<KeyRound aria-hidden="true" className="size-4" />}>
            Код привʼязки
          </Button>
        }
      />

      <BotStatusPanel bindings={bindings.data} />

      <Panel title="Привʼязані чати">
        <BindingsTable bindings={bindings.data} loading={bindings.isPending} error={bindings.error} onRetry={() => void bindings.refetch()} />
      </Panel>

      <Panel id="deliveries" title="Журнал розсилки">
        <DeliveriesTable />
      </Panel>

      <BindingCodeDialog open={codeOpen} onOpenChange={setCodeOpen} />
    </div>
  );
}
