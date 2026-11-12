import { Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';

/**
 * Силует дошки: дві сітки з рядками пар. Один і той самий поки вантажаться версії (сторінка) і поки вантажаться
 * довідники й заняття (редактор) — тож між цими етапами на екрані нічого не змінює форму. Обгортку з `role="status"`
 * ставить той, хто показує силует.
 */
export function BoardSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-4">
      {[0, 1].map((grid) => (
        <div key={grid} className="rounded-panel border border-default bg-surface">
          <div className="flex items-center gap-3 border-b border-separator px-3 py-2.5">
            <Skeleton className="h-6 w-14" />
            <Skeleton className="h-4 w-28" />
          </div>
          <div className="grid grid-cols-[2.75rem_repeat(5,minmax(0,1fr))] gap-1 p-1">
            {Array.from({ length: 24 }, (_, cell) => (
              <Skeleton key={cell} className={cn('h-14', cell % 6 === 0 && 'w-8 justify-self-center')} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
