import { Skeleton } from '@/components/ui';

/** Силует заголовка, поки не відомо, чий це розклад: «‹ Розклад», код і рядок контексту. */
export function ScheduleHeaderSkeleton() {
  return (
    <div className="flex flex-col gap-3" aria-hidden="true">
      <Skeleton className="h-4 w-24" />
      <Skeleton className="h-10 w-40" />
      <Skeleton className="h-4 w-72 max-w-full" />
    </div>
  );
}
