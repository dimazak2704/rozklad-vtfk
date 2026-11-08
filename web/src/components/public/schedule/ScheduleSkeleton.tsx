import { LoadingRegion, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import type { ScheduleView } from './schedule-params';

/** Силует дня: велика цифра пари, час і клітинка заняття — як у DayList. */
function DaySilhouette({ rows = 4 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-3">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="grid grid-cols-[2.75rem_1.25rem_minmax(0,1fr)] items-start gap-x-2.5">
          <div className="flex flex-col items-end gap-1.5 pt-1.5">
            <Skeleton className="h-6 w-5" />
            <Skeleton className="h-3 w-9" />
          </div>
          <div className="flex justify-center pt-3">
            <Skeleton className="size-3.5 rounded-full" />
          </div>
          <Skeleton className={cn('h-[4.25rem] rounded-control', index % 2 === 0 ? 'w-full' : 'w-11/12')} />
        </div>
      ))}
    </div>
  );
}

/** Силует тижневої сітки: колонка пар і пʼять днів. */
function WeekSilhouette() {
  return (
    <div className="overflow-hidden rounded-panel border border-default bg-surface">
      <div className="grid grid-cols-[6.75rem_repeat(5,minmax(0,1fr))] border-b border-default bg-sunken">
        <div className="px-2 py-2.5">
          <Skeleton className="h-3.5 w-16" />
        </div>
        {Array.from({ length: 5 }, (_, index) => (
          <div key={index} className="px-3 py-2.5">
            <Skeleton className="h-3.5 w-20" />
          </div>
        ))}
      </div>
      {Array.from({ length: 4 }, (_, row) => (
        <div key={row} className="grid grid-cols-[6.75rem_repeat(5,minmax(0,1fr))] border-b border-separator last:border-b-0">
          <div className="flex flex-col gap-1.5 px-2 py-2">
            <Skeleton className="h-6 w-5" />
            <Skeleton className="h-3 w-16" />
          </div>
          {Array.from({ length: 5 }, (_, col) => (
            <div key={col} className="p-1">
              {(row + col) % 4 === 3 ? null : <Skeleton className="h-14 w-full rounded-plate" />}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

/** Завантаження розкладу: силует саме того подання, що зʼявиться. */
export function ScheduleSkeleton({ view }: { view: ScheduleView }) {
  return (
    <LoadingRegion label="Завантажуємо розклад…">
      {view === 'week' ? (
        <>
          <div className="hidden md:block">
            <WeekSilhouette />
          </div>
          <div className="md:hidden">
            <DaySilhouette />
          </div>
        </>
      ) : (
        <div className="max-w-3xl">
          <DaySilhouette />
        </div>
      )}
    </LoadingRegion>
  );
}
