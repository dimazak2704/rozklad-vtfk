import { Hourglass, RotateCcw, WifiOff } from 'lucide-react';
import { Button, useErrorCovered } from '@/components/ui';
import { cn } from '@/lib/cn';
import { useOnline } from '../useOnline';
import { useStalled, type StallableQuery } from '../useStalled';

interface RefetchableQuery extends StallableQuery {
  refetch: () => unknown;
}

/**
 * Один спокійний рядок замість нескінченного силуету: без мережі — що показуємо збережене і що решта
 * підвантажиться сама; якщо сервер довго мовчить — що сталося й кнопка «Спробувати ще раз».
 * Коли сторінка вже показує спільну помилку, мовчить.
 */
export function StallNote({ queries, className }: { queries: readonly (RefetchableQuery | null | undefined)[]; className?: string }) {
  const covered = useErrorCovered();
  const online = useOnline();
  const stall = useStalled(queries);
  if (covered) return null;
  const offline = stall === 'offline' || !online;
  if (!offline && stall !== 'slow') return null;
  const hasData = queries.some((q) => q && !q.isPending);

  return (
    <p role="status" className={cn('flex flex-wrap items-center gap-x-3 gap-y-1 text-ui text-secondary', className)}>
      <span className="inline-flex items-start gap-2">
        {offline ? (
          <WifiOff aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
        ) : (
          <Hourglass aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
        )}
        {offline
          ? hasData
            ? 'Немає звʼязку — показуємо збережений розклад. Решта зʼявиться, щойно буде інтернет.'
            : 'Немає звʼязку. Розклад завантажиться сам, щойно зʼявиться інтернет.'
          : 'Сервер довго не відповідає.'}
      </span>
      {!offline ? (
        <Button
          variant="link"
          size="sm"
          iconStart={<RotateCcw aria-hidden="true" className="size-3.5" />}
          onClick={() => queries.forEach((q) => (q && q.isPending && q.fetchStatus !== 'idle' ? void q.refetch() : undefined))}
        >
          Спробувати ще раз
        </Button>
      ) : null}
    </p>
  );
}
