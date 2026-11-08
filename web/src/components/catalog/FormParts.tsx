import { CircleExclamationPoint } from 'lucide-react';
import type { ReactNode } from 'react';
import { Button, Dialog, DialogContent } from '@/components/ui';
import { cn } from '@/lib/cn';
import { confirm } from '@/stores/confirm';

/** Помилка всієї форми (дублікат, «дані змінилися», збій сервера) — над полями, читач екрана почує її одразу. */
export function FormAlert({ message, className }: { message: string | null | undefined; className?: string }) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className={cn('flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2.5 text-ui text-danger', className)}
    >
      <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4" />
      <p className="min-w-0 font-medium">{message}</p>
    </div>
  );
}

export interface FormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  /** id елемента <form>: кнопка «Зберегти» у підвалі надсилає саме її. */
  formId: string;
  submitLabel: string;
  pending: boolean;
  /** Є незбережені зміни — закриття питає підтвердження. */
  dirty: boolean;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Дія ліворуч у підвалі (наприклад, «Видалити»). */
  secondaryAction?: ReactNode;
  children: ReactNode;
}

/**
 * Діалог з формою: Esc, хрестик і «Скасувати» не гублять введене мовчки — якщо є зміни, спершу питаємо.
 * Поки йде збереження, закрити не можна (інакше результат запиту загубиться).
 */
export function FormDialog({
  open,
  onOpenChange,
  title,
  description,
  formId,
  submitLabel,
  pending,
  dirty,
  size = 'md',
  secondaryAction,
  children,
}: FormDialogProps) {
  const requestClose = async () => {
    if (pending) return;
    if (
      dirty &&
      !(await confirm({
        title: 'Закрити без збереження?',
        description: 'Введені зміни буде втрачено.',
        confirmLabel: 'Закрити',
        cancelLabel: 'Повернутися до форми',
        tone: 'danger',
      }))
    ) {
      return;
    }
    onOpenChange(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (next) onOpenChange(true);
        else void requestClose();
      }}
    >
      <DialogContent
        title={title}
        description={description}
        size={size}
        // фокус — у перше поле форми, а не на хрестик «Закрити»
        onOpenAutoFocus={(event) => {
          const field = (event.currentTarget as HTMLElement | null)?.querySelector<HTMLElement>(
            'form input:not([type="hidden"]):not([disabled]), form textarea, form button[role="combobox"]',
          );
          if (field) {
            event.preventDefault();
            field.focus();
          }
        }}
        footer={
          <>
            {secondaryAction ? <span className="me-auto">{secondaryAction}</span> : null}
            <Button variant="secondary" onClick={() => void requestClose()} disabled={pending}>
              Скасувати
            </Button>
            <Button type="submit" form={formId} variant="primary" loading={pending} loadingLabel="Зберігаємо">
              {submitLabel}
            </Button>
          </>
        }
      >
        {children}
      </DialogContent>
    </Dialog>
  );
}

/** Підпис-пояснення поруч зі значенням у таблиці: «—» для порожнього з поясненням для читача екрана. */
export function EmptyValue({ label = 'не вказано' }: { label?: string }) {
  return (
    <span className="text-muted">
      <span aria-hidden="true">—</span>
      <span className="sr-only">{label}</span>
    </span>
  );
}
