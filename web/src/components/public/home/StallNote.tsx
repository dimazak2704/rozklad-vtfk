import { useQueryClient } from '@tanstack/react-query';
import { Hourglass, RotateCcw, WifiOff } from 'lucide-react';
import { qk } from '@/api/queryKeys';
import { savedCopyTimeLabel, useAnySavedCopy } from '@/api/saved-copy';
import { Button, useErrorCovered } from '@/components/ui';
import { cn } from '@/lib/cn';
import { SavedCopyNote } from '../SavedCopyNote';
import { useOnline } from '../useOnline';
import { useStalled, type StallableQuery } from '../useStalled';

interface RefetchableQuery extends StallableQuery {
  refetch: () => unknown;
}

/**
 * Один спокійний рядок замість нескінченного силуету: без мережі — що показуємо збережене і що решта
 * підвантажиться сама; якщо сервер довго мовчить — що сталося й кнопка «Спробувати ще раз».
 * Мережа є, але сервер не відповів і Service Worker віддав збережену копію (`savedUrls` — адреси запитів,
 * як їх будує buildUrl), — так і каже, з часом копії. Коли сторінка вже показує спільну помилку, мовчить.
 */
export function StallNote({
  queries,
  savedUrls = [],
  className,
}: {
  queries: readonly (RefetchableQuery | null | undefined)[];
  savedUrls?: readonly (string | undefined)[];
  className?: string;
}) {
  const covered = useErrorCovered();
  const online = useOnline();
  const stall = useStalled(queries);
  const savedCopy = useAnySavedCopy(savedUrls);
  const queryClient = useQueryClient();
  if (covered) return null;
  const offline = stall === 'offline' || !online;
  if (!offline && stall !== 'slow') {
    if (!savedCopy.fromCache) return null;
    return (
      // у блоків сторінки є й власні запити (стан дня бере два дні) — оновлюємо всі публічні на екрані
      <SavedCopyNote className={className} online onRefresh={() => void queryClient.refetchQueries({ queryKey: qk.public.all, type: 'active' })}>
        {savedCopy.savedAt !== null
          ? `Показано розклад, збережений ${savedCopyTimeLabel(savedCopy.savedAt)} — заміни могли змінитися.`
          : 'Показано збережений розклад — заміни могли змінитися.'}
      </SavedCopyNote>
    );
  }
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
