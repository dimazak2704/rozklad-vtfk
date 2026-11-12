import { useIsFetching } from '@tanstack/react-query';
import { CircleExclamationPoint, Send, TriangleExclamationPoint } from 'lucide-react';
import { useState } from 'react';
import { isStaleDraft } from '@/api/admin-daily';
import { isApiError } from '@/api/errors';
import { qk } from '@/api/queryKeys';
import type { OrderDto } from '@/api/types';
import { Button, Dialog, DialogContent, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
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

export const STALE_DRAFT_MESSAGE = 'Чернетку оновлено — перевірте зміни й опублікуйте ще раз.';

/**
 * Підтвердження публікації: який номер отримає розпорядження, що в ньому і що станеться далі.
 * Номер остаточно присвоює сервер; якщо його вже зайняв колега, у тості буде справжній.
 */
export function PublishDialog({ open, onOpenChange, order, parentNumber, nextNumber, pending, declined, onPublish }: PublishDialogProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // сервер відмовив: чернетка застаріла; її вже перечитують — поки нові рядки не прийшли, не публікуємо
  const [stale, setStale] = useState(false);
  const refreshing = useIsFetching({ queryKey: qk.admin.orders.draft(order.date) }) > 0;
  const rows = order.items.length + order.roomChanges.length;
  const kind = order.kind === 'AMENDMENT' ? 'доповнення' : 'розпорядження';
  const numberText = nextNumber !== null ? `№${nextNumber}` : null;

  /** Закриття будь-яким шляхом (кнопка, Esc, хрестик) забуває відмову: знову відкритий діалог — чистий. */
  const close = () => {
    setError(null);
    setStale(false);
    onOpenChange(false);
  };

  const publish = async () => {
    setBusy(true);
    setError(null);
    setStale(false);
    try {
      await onPublish();
      onOpenChange(false);
    } catch (cause) {
      if (isStaleDraft(cause)) {
        setStale(true);
        setError(STALE_DRAFT_MESSAGE);
      } else {
        setError(isApiError(cause) ? cause.message : 'Не вдалося опублікувати. Спробуйте ще раз.');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (busy) return;
        if (next) onOpenChange(true);
        else close();
      }}
    >
      <DialogContent
        title={`Опублікувати ${kind}?`}
        description="Після публікації документ не змінюється: нові зміни підуть окремим доповненням."
        footer={
          <>
            <Button variant="secondary" onClick={close} disabled={busy}>
              {stale ? 'Переглянути чернетку' : 'Скасувати'}
            </Button>
            <Button
              variant="primary"
              loading={busy || (stale && refreshing)}
              onClick={() => void publish()}
              iconStart={<Send aria-hidden="true" className="size-4" />}
            >
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
            <p role="alert" className={cn('flex items-start gap-2 text-ui font-medium', stale ? 'text-change' : 'text-danger')}>
              {stale ? (
                <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              ) : (
                <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              )}
              {error}
            </p>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
