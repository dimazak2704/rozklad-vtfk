import { Link } from 'react-router';
import { MAX_DELIVERIES_PAGE, useDeliveries } from '@/api/telegram';
import type { DeliveryDto, Uuid } from '@/api/types';
import { SkeletonText, StatusDot } from '@/components/ui';
import { countLabel } from '@/lib/labels';
import { deliveryChat } from '../telegram/labels';

const CHATS = ['чат', 'чати', 'чатів'] as const;
const MESSAGES = ['повідомлення', 'повідомлення', 'повідомлень'] as const;

type ChatState = 'sent' | 'queued' | 'failed';

/**
 * Стан кожного чату за його повідомленнями: чат отримує текст і зображення, тож рахуємо чати, а не рядки
 * журналу. Хоч одне не доставлено — чат «не доставлено»; щось ще в черзі — «у черзі»; інакше — надіслано.
 */
function chatStates(list: readonly DeliveryDto[]): Array<{ chat: DeliveryDto; state: ChatState }> {
  const chats = new Map<string, { chat: DeliveryDto; state: ChatState }>();
  for (const delivery of list) {
    const key = `${delivery.chatId}|${delivery.chatTitle ?? ''}`;
    const state: ChatState =
      delivery.status === 'FAILED' ? 'failed' : delivery.status === 'QUEUED' || delivery.status === 'RETRYING' ? 'queued' : 'sent';
    const known = chats.get(key);
    if (!known) chats.set(key, { chat: delivery, state });
    else if (state === 'failed' || (state === 'queued' && known.state === 'sent')) known.state = state;
  }
  return [...chats.values()];
}

/** Як розійшлося розпорядження в Telegram: скільки чатів отримали, що ще в черзі, де помилки. */
export function DeliverySummary({ orderId }: { orderId: Uuid }) {
  // одне розпорядження — десятки чатів, тож однієї найбільшої сторінки досить; якщо ні, чесно кажемо, що це частина
  const deliveries = useDeliveries({ orderId, size: MAX_DELIVERIES_PAGE });
  if (deliveries.isPending) return <SkeletonText lines={2} />;
  if (deliveries.isError) return <p className="text-caption text-muted">Журнал розсилки зараз недоступний.</p>;
  const list = deliveries.data.items;
  const partial = deliveries.data.total > list.length;
  if (list.length === 0) {
    return <p className="text-caption text-secondary">Жодна група з цими змінами не привʼязала чат до бота — розсилки не було.</p>;
  }
  const chats = chatStates(list);
  const sent = chats.filter((c) => c.state === 'sent').length;
  const queued = chats.filter((c) => c.state === 'queued').length;
  const failed = chats.filter((c) => c.state === 'failed').map((c) => c.chat);
  const sentMessages = list.filter((d) => d.status === 'SENT').length;
  return (
    <div className="flex flex-col gap-1.5 text-ui">
      <StatusDot
        tone="add"
        label={`Надіслано: ${countLabel(sent, CHATS)}${sentMessages !== sent ? ` (${countLabel(sentMessages, MESSAGES)})` : ''}`}
        showLabel
      />
      {queued > 0 ? <StatusDot tone="change" pulse label={`У черзі: ${countLabel(queued, CHATS)}`} showLabel /> : null}
      {failed.length > 0 ? (
        <StatusDot tone="cancel" label={`Не доставлено: ${failed.map(deliveryChat).join(', ')}`} showLabel />
      ) : null}
      {partial ? (
        <p className="text-caption text-muted figures">
          Пораховано останні {list.length} з {deliveries.data.total} повідомлень.
        </p>
      ) : null}
      <Link to="/admin/telegram#deliveries" className="mt-1 text-caption font-semibold text-accent underline-offset-4 hover-fine:underline">
        Журнал розсилки
      </Link>
    </div>
  );
}
