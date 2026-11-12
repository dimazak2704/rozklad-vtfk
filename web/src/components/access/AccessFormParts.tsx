import { CircleExclamationPoint } from 'lucide-react';
import type { ReactNode } from 'react';
import { Button, Dialog, DialogContent } from '@/components/ui';

/** Помилка всієї форми (EMAIL_TAKEN, LAST_ADMIN, збій сервера): над кнопками, читач екрана почує її одразу. */
export function AccessFormAlert({ message }: { message: string | null | undefined }) {
  if (!message) return null;
  return (
    <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2.5 text-ui text-danger">
      <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span className="min-w-0 font-medium">{message}</span>
    </p>
  );
}

interface AccessDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  formId: string;
  submitLabel: string;
  pending: boolean;
  children: ReactNode;
}

/** Діалог з формою «Доступів»: «Скасувати» і головна дія внизу; поки йде запит, закрити не можна. */
export function AccessDialog({ open, onOpenChange, title, formId, submitLabel, pending, children }: AccessDialogProps) {
  return (
    <Dialog open={open} onOpenChange={(next) => (pending ? undefined : onOpenChange(next))}>
      <DialogContent
        title={title}
        size="md"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={pending}>
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
