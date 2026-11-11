import { RotateCcw } from 'lucide-react';
import type { EventStreamState } from '@/api/useEventStream';
import { cn } from '@/lib/cn';
import { Button } from '../ui/Button';

/**
 * Стан живих оновлень (SSE) у верхній панелі — лише коли щось не так, як у Google Docs: спокійний рядок
 * «крапка + слово», а не банер. Здорове зʼєднання нічого не показує («Онлайн» не пишемо); перепідключення —
 * тихе слово (на телефоні лише крапка, щоб не витісняти дату); немає звʼязку — червона крапка й «Повторити».
 */
export function LiveStatus({
  state,
  onRetry,
  className,
}: {
  state: EventStreamState;
  /** Повторити підключення; без нього сторінка перезавантажується. */
  onRetry?: () => void;
  className?: string;
}) {
  // одна жива область на весь час: вміст змінюється, тож читач екрана почує «Офлайн» чи «Перепідключення»
  return (
    <span aria-live="polite" data-live-status={state.status} className={cn('inline-flex items-center gap-1 empty:hidden', className)}>
      {state.status === 'reconnecting' ? (
        <span className="inline-flex items-center gap-2 text-caption text-muted">
          <span aria-hidden="true" className="size-2 rounded-full bg-change" />
          <span className="max-sm:sr-only">Перепідключення…</span>
        </span>
      ) : null}
      {state.status === 'closed' ? (
        <>
          <span className="inline-flex items-center gap-2 text-caption font-[650] text-primary">
            <span aria-hidden="true" className="size-2 rounded-full bg-[var(--fill-cancel)]" />
            <span className="max-sm:sr-only">
              Немає звʼязку<span className="hidden xl:inline"> — дані можуть бути застарілими</span>
            </span>
          </span>
          <Button
            variant="ghost"
            size="sm"
            iconStart={<RotateCcw aria-hidden="true" className="size-4" />}
            onClick={() => (onRetry ? onRetry() : window.location.reload())}
          >
            Повторити
          </Button>
        </>
      ) : null}
    </span>
  );
}
