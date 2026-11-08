import { Ban, TriangleExclamationPoint } from 'lucide-react';
import { AlertDialog } from 'radix-ui';
import { useState } from 'react';
import { errorMessage, isApiError } from '@/api/errors';
import { Button, notify } from '@/components/ui';
import { cn } from '@/lib/cn';

export interface DeleteTarget {
  id: string;
  /** Як запис називається для людини: «аудиторія 301», «викладач Кренцін М.Д.». */
  name: string;
}

interface DeleteDialogProps {
  target: DeleteTarget | null;
  onClose: () => void;
  /** «Видалити аудиторію?» */
  title: string;
  /** Що станеться: «Аудиторія зникне з довідника. Розклад і навантаження її не втратять». */
  description?: string;
  onDelete: (id: string) => Promise<unknown>;
  successMessage: (name: string) => string;
  /**
   * Якщо запис використано, сервер не видаляє його (409) і радить вимкнути. Тоді діалог пропонує
   * це зробити однією кнопкою.
   */
  deactivate?: { label: string; run: (id: string) => Promise<unknown>; successMessage: (name: string) => string };
}

type Phase = { kind: 'confirm' } | { kind: 'blocked'; reason: string } | { kind: 'error'; message: string };

/**
 * Видалення з підтвердженням і зрозумілим поясненням, чому не вийшло: 409 від сервера показуємо як
 * «Не можна видалити: …» з наступним кроком, а не як збій. Фокус спершу на «Скасувати».
 */
export function DeleteDialog({ target, onClose, title, description, onDelete, successMessage, deactivate }: DeleteDialogProps) {
  const [phase, setPhase] = useState<Phase>({ kind: 'confirm' });
  const [pending, setPending] = useState(false);

  const close = () => {
    if (pending) return;
    setPhase({ kind: 'confirm' });
    onClose();
  };

  const run = async (action: () => Promise<unknown>, success: string) => {
    setPending(true);
    try {
      await action();
      notify.success(success);
      setPhase({ kind: 'confirm' });
      onClose();
    } catch (error) {
      if (isApiError(error) && error.status === 409) setPhase({ kind: 'blocked', reason: error.message });
      else setPhase({ kind: 'error', message: errorMessage(error) });
    } finally {
      setPending(false);
    }
  };

  if (!target) return null;
  const blocked = phase.kind === 'blocked';

  return (
    <AlertDialog.Root
      open
      onOpenChange={(open) => {
        if (!open) close();
      }}
    >
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim" />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-3">
          <AlertDialog.Content className="anim-dialog pointer-events-auto w-full max-w-md rounded-dialog border border-default bg-raised p-5 text-primary shadow-dialog focus-visible:outline-none">
            <div className="flex gap-3">
              {blocked ? (
                <Ban aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-change" />
              ) : (
                <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-danger" />
              )}
              <div className="flex min-w-0 flex-col gap-2">
                <AlertDialog.Title className="text-heading font-semibold">
                  {blocked ? 'Видалити не можна' : title}
                </AlertDialog.Title>
                <AlertDialog.Description asChild>
                  <div className="flex flex-col gap-2 text-ui text-secondary">
                    <p className="rounded-control border border-default bg-sunken px-3 py-2 font-semibold break-words text-primary">
                      {target.name}
                    </p>
                    {blocked ? (
                      <p className="text-primary">{phase.reason}</p>
                    ) : description ? (
                      <p>{description}</p>
                    ) : null}
                  </div>
                </AlertDialog.Description>
                {phase.kind === 'error' ? (
                  <p role="alert" className="text-caption font-medium text-danger">
                    {phase.message}
                  </p>
                ) : null}
              </div>
            </div>
            <div className={cn('mt-5 flex flex-wrap justify-end gap-2')}>
              <AlertDialog.Cancel asChild>
                <Button variant="secondary" disabled={pending}>
                  {blocked ? 'Закрити' : 'Скасувати'}
                </Button>
              </AlertDialog.Cancel>
              {blocked ? (
                deactivate ? (
                  <Button
                    variant="primary"
                    loading={pending}
                    onClick={() => void run(() => deactivate.run(target.id), deactivate.successMessage(target.name))}
                  >
                    {deactivate.label}
                  </Button>
                ) : null
              ) : (
                <Button variant="danger" loading={pending} onClick={() => void run(() => onDelete(target.id), successMessage(target.name))}>
                  Видалити
                </Button>
              )}
            </div>
          </AlertDialog.Content>
        </div>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}
