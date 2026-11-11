import { Check, Copy } from 'lucide-react';
import { useState } from 'react';
import { isApiError } from '@/api/errors';
import { cn } from '@/lib/cn';
import { Button } from './Button';

/**
 * «Подробиці» для служби підтримки: код запиту сховано за тихою кнопкою, бо студентові він нічого
 * не каже. Розкрито — код і «Копіювати», щоб переслати його диспетчеру одним натисканням.
 */
export function ErrorDetails({ error, className }: { error: unknown; className?: string }) {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const requestId = isApiError(error) ? error.requestId : undefined;
  if (!requestId) return null;

  const copy = () => {
    void navigator.clipboard?.writeText(requestId).then(
      () => setCopied(true),
      () => setCopied(false),
    );
  };

  return (
    <span className={cn('inline-flex flex-wrap items-center gap-x-2 gap-y-1', className)}>
      <Button variant="ghost" size="sm" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        {open ? 'Сховати подробиці' : 'Подробиці'}
      </Button>
      {open ? (
        <span className="inline-flex min-w-0 items-center gap-1 text-caption text-muted">
          <span className="min-w-0 break-all">
            Код запиту <span className="font-mono select-all">{requestId}</span>
          </span>
          <Button
            variant="ghost"
            size="sm"
            onClick={copy}
            aria-label={copied ? 'Код запиту скопійовано' : 'Копіювати код запиту'}
            className="px-2"
          >
            {copied ? <Check aria-hidden="true" className="size-4 text-add" /> : <Copy aria-hidden="true" className="size-4" />}
          </Button>
        </span>
      ) : null}
    </span>
  );
}
