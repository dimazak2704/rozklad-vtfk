import { CircleExclamationPoint, Send, TriangleExclamationPoint } from 'lucide-react';
import { useState } from 'react';
import { isApiError } from '@/api/errors';
import type { OrderDto } from '@/api/types';
import { Button, Dialog, DialogContent, Skeleton } from '@/components/ui';
import { formatLongDate } from '@/lib/date';
import { countLabel } from '@/lib/labels';

interface PublishDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  order: OrderDto;
  parentNumber: number | null;
  /** Номер, який, найімовірніше, присвоїть сервер (останній у семестрі + 1); null — ще рахуємо. */
  nextNumber: number | null;
  pending: number;
  declined: number;
  onPublish: () => Promise<OrderDto>;
}

const ROWS = ['рядок', 'рядки', 'рядків'] as const;
const NOTES = ['примітка', 'примітки', 'приміток'] as const;
const CHANGES = ['заміна', 'заміни', 'замін'] as const;

/**
 * Підтвердження публікації: який номер отримає розпорядження, що в ньому і що станеться далі.
 * Номер остаточно присвоює сервер; якщо його вже зайняв колега, у тості буде справжній.
 */
export function PublishDialog({ open, onOpenChange, order, parentNumber, nextNumber, pending, declined, onPublish }: PublishDialogProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const rows = order.items.length + order.roomChanges.length;
  const kind = order.kind === 'AMENDMENT' ? 'доповнення' : 'розпорядження';
  const numberText = nextNumber !== null ? `№${nextNumber}` : null;

  const publish = async () => {
    setBusy(true);
    setError(null);
    try {
      await onPublish();
      onOpenChange(false);
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : 'Не вдалося опублікувати. Спробуйте ще раз.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (busy) return;
        if (!next) setError(null);
        onOpenChange(next);
      }}
    >
      <DialogContent
        title={`Опублікувати ${kind}?`}
        description="Після публікації документ не змінюється: нові зміни підуть окремим доповненням."
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={busy}>
              Скасувати
            </Button>
            <Button variant="primary" loading={busy} onClick={() => void publish()} iconStart={<Send aria-hidden="true" className="size-4" />}>
              {numberText ? `Опублікувати ${numberText}` : 'Опублікувати'}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="flex items-center gap-4 rounded-panel border border-default bg-sunken px-4 py-3">
            <span className="flex flex-col">
              <span className="text-caption text-muted">Буде присвоєно</span>
              {numberText ? (
                <span className="font-condensed text-display leading-none font-bold text-primary figures">{numberText}</span>
              ) : (
                <Skeleton className="mt-1 h-9 w-20" />
              )}
            </span>
            <span className="flex min-w-0 flex-col text-ui text-secondary">
              <span className="font-semibold text-primary">
                {order.kind === 'AMENDMENT' ? `Доповнення до №${parentNumber ?? '—'}` : 'Розпорядження'} на {formatLongDate(order.date)}
              </span>
              <span>
                {countLabel(rows, ROWS)}
                {order.notes.length > 0 ? `, ${countLabel(order.notes.length, NOTES)}` : ''} · підпис:{' '}
                {order.signatoryName}
              </span>
            </span>
          </div>

          <ul className="flex flex-col gap-1.5 text-ui text-secondary">
            <li>Підтверджені заміни внесуться в розклад груп і викладачів.</li>
            <li>Групи з привʼязаним чатом отримають свої рядки й зображення в Telegram.</li>
            <li>Номер — наскрізний у семестрі, тому скасувати публікацію не можна.</li>
          </ul>

          {pending > 0 || declined > 0 ? (
            <p className="flex items-start gap-2 rounded-control border border-change bg-change-subtle px-3 py-2 text-ui text-change">
              <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span>
                {pending > 0 ? `${countLabel(pending, CHANGES)} ще ${pending === 1 ? 'чекає' : 'чекають'} підтвердження` : ''}
                {pending > 0 && declined > 0 ? ' і ' : ''}
                {declined > 0 ? `${declined} без нового кандидата після відмови` : ''} — у цей документ не потраплять, лише в доповнення.
              </span>
            </p>
          ) : null}

          {error ? (
            <p role="alert" className="flex items-start gap-2 text-ui font-medium text-danger">
              <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              {error}
            </p>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
