import { Link } from 'react-router';
import { useDeliveries } from '@/api/telegram';
import type { Uuid } from '@/api/types';
import { SkeletonText, StatusDot } from '@/components/ui';
import { countLabel } from '@/lib/labels';

const CHATS = ['чат', 'чати', 'чатів'] as const;

/** Як розійшлося розпорядження в Telegram: скільки чатів отримали, що ще в черзі, де помилки. */
export function DeliverySummary({ orderId }: { orderId: Uuid }) {
  const deliveries = useDeliveries({ orderId });
  if (deliveries.isPending) return <SkeletonText lines={2} />;
  if (deliveries.isError) return <p className="text-caption text-muted">Журнал розсилки зараз недоступний.</p>;
  const list = deliveries.data;
  if (list.length === 0) {
    return <p className="text-caption text-secondary">Жодна група з цими змінами не привʼязала чат до бота — розсилки не було.</p>;
  }
  const sent = list.filter((d) => d.status === 'SENT').length;
  const queued = list.filter((d) => d.status === 'QUEUED' || d.status === 'RETRYING').length;
  const failed = list.filter((d) => d.status === 'FAILED');
  return (
    <div className="flex flex-col gap-1.5 text-ui">
      <StatusDot tone="add" label={`Надіслано: ${countLabel(sent, CHATS)}`} showLabel />
      {queued > 0 ? <StatusDot tone="change" pulse label={`У черзі: ${countLabel(queued, CHATS)}`} showLabel /> : null}
      {failed.length > 0 ? (
        <StatusDot tone="cancel" label={`Не доставлено: ${failed.map((d) => d.chatTitle).join(', ')}`} showLabel />
      ) : null}
      <Link to="/admin/telegram#deliveries" className="mt-1 text-caption font-semibold text-accent underline-offset-4 hover:underline">
        Журнал розсилки
      </Link>
    </div>
  );
}
