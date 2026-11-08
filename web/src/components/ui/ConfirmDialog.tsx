import { TriangleExclamationPoint } from 'lucide-react';
import { AlertDialog } from 'radix-ui';
import { useState, type ReactNode } from 'react';
import { errorMessage } from '@/api/errors';
import { cn } from '@/lib/cn';
import { useConfirmStore } from '@/stores/confirm';
import { Button } from './Button';

export interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  objectName?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'default' | 'danger';
  /** Може повертати Promise — тоді кнопка показує завантаження, а помилка лишається в діалозі. */
  onConfirm: () => unknown;
}

/**
 * Підтвердження дії. Для небезпечних дій (`tone="danger"`) називає обʼєкт, щоб не видалити не те.
 * Фокус за замовчуванням на «Не зараз», щоб Enter випадково не підтвердив. «Скасувати …» лишаємо для
 * доменних дій («Скасувати заміну»), щоб кнопка закриття з ними не плуталась.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  objectName,
  confirmLabel = 'Підтвердити',
  cancelLabel = 'Не зараз',
  tone = 'default',
  onConfirm,
}: ConfirmDialogProps) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleConfirm = async () => {
    setPending(true);
    setError(null);
    try {
      await onConfirm();
      onOpenChange(false);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setPending(false);
    }
  };

  return (
    <AlertDialog.Root
      open={open}
      onOpenChange={(next) => {
        if (pending) return;
        if (!next) setError(null);
        onOpenChange(next);
      }}
    >
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim" />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-3">
          <AlertDialog.Content
            className={cn(
              'anim-dialog pointer-events-auto w-full max-w-md rounded-dialog border border-default bg-raised p-5 text-primary shadow-dialog',
              'focus-visible:outline-none',
            )}
          >
            <div className="flex gap-3">
              {tone === 'danger' ? (
                <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-danger" />
              ) : null}
              <div className="flex min-w-0 flex-col gap-2">
                <AlertDialog.Title className="text-heading font-semibold">{title}</AlertDialog.Title>
                {description || objectName ? (
                  <AlertDialog.Description asChild>
                    <div className="flex flex-col gap-2 text-ui text-secondary">
                      {description ? <p>{description}</p> : null}
                      {objectName ? (
                        <p className="rounded-control bg-sunken px-3 py-2 font-medium break-words text-primary">
                          {objectName}
                        </p>
                      ) : null}
                    </div>
                  </AlertDialog.Description>
                ) : null}
                {error ? (
                  <p role="alert" className="text-caption font-medium text-danger">
                    {error}
                  </p>
                ) : null}
              </div>
            </div>
            <div className="mt-5 flex flex-wrap justify-end gap-2">
              <AlertDialog.Cancel asChild>
                <Button variant="secondary" disabled={pending}>
                  {cancelLabel}
                </Button>
              </AlertDialog.Cancel>
              <Button
                variant={tone === 'danger' ? 'danger' : 'primary'}
                loading={pending}
                onClick={() => void handleConfirm()}
              >
                {confirmLabel}
              </Button>
            </div>
          </AlertDialog.Content>
        </div>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}

/** Хост для імперативного `confirm()` зі stores/confirm. Один на застосунок. */
export function ConfirmDialogHost() {
  const current = useConfirmStore((state) => state.current);
  const settle = useConfirmStore((state) => state.settle);
  if (!current) return null;
  return (
    <ConfirmDialog
      key={current.id}
      open
      onOpenChange={(open) => {
        if (!open) settle(false);
      }}
      title={current.title}
      description={current.description}
      objectName={current.objectName}
      confirmLabel={current.confirmLabel}
      cancelLabel={current.cancelLabel}
      tone={current.tone}
      onConfirm={async () => {
        await current.onConfirm?.();
        settle(true);
      }}
    />
  );
}
