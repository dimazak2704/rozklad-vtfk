import { RotateCcw } from 'lucide-react';
import type { EventStreamState } from '@/api/useEventStream';
import { cn } from '@/lib/cn';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';

/**
 * Стан живих оновлень (SSE) у верхній панелі — лише коли щось не так, як у Google Docs.
 * Здорове зʼєднання нічого не показує; перепідключення — бурштинова позначка; офлайн — червона
 * з поясненням і «Повторити». Голої крапки без слова немає.
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
      {state.status === 'reconnecting' ? <Badge tone="warning">Перепідключення…</Badge> : null}
      {state.status === 'closed' ? (
        <>
          <Badge tone="danger">
            <span className="hidden xl:inline">Офлайн — дані можуть бути застарілими</span>
            <span className="xl:hidden">Офлайн</span>
          </Badge>
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
