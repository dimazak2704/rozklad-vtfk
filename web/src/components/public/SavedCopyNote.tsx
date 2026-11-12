import { RotateCcw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { Button } from '@/components/ui';
import { cn } from '@/lib/cn';

/**
 * Рядок «показано збережене»: без інтернету або коли сервер не відповів і Service Worker віддав копію
 * зі свого кешу. Онлайн поруч — «Оновити»; без мережі кнопка нічого б не дала.
 * `children` — друге речення: «Показано заміни, збережені вчора о 19:40 — могли змінитися.»
 */
export function SavedCopyNote({
  online,
  onRefresh,
  className,
  children,
}: {
  online: boolean;
  onRefresh: () => void;
  className?: string;
  children: ReactNode;
}) {
  return (
    <p
      role="status"
      className={cn(
        'flex items-start gap-2 rounded-control border border-default bg-sunken px-3 py-2 text-caption text-secondary',
        className,
      )}
    >
      <WifiOff aria-hidden="true" className="mt-px size-4 shrink-0 text-muted" />
      <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span>
          {online ? 'Сервер не відповів.' : 'Немає інтернету.'} {children}
        </span>
        {online ? (
          <Button variant="link" size="sm" onClick={onRefresh} iconStart={<RotateCcw aria-hidden="true" className="size-3.5" />}>
            Оновити
          </Button>
        ) : null}
      </span>
    </p>
  );
}
