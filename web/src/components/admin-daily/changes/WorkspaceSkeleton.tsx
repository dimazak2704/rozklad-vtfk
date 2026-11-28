import { Skeleton } from '@/components/ui';

/** Рядок відсутнього в силуеті: імʼя, підпис і смужка «вирішено». */
function AbsenceRowSkeleton() {
  return (
    <div className="flex flex-col gap-2 px-3 py-2.5">
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-3 w-1/2" />
      <Skeleton className="h-2.5 w-28" />
    </div>
  );
}

/** Рядок пари в силуеті: час ліворуч, група з дисципліною й стан праворуч. */
function LessonRowSkeleton() {
  return (
    <div className="grid grid-cols-[3.25rem_minmax(0,1fr)] gap-x-3 px-3 py-2.5">
      <div className="flex flex-col gap-1.5">
        <Skeleton className="h-4 w-11" />
        <Skeleton className="h-3 w-9" />
      </div>
      <div className="flex flex-col gap-1.5">
        <Skeleton className="h-4 w-3/4" />
        <Skeleton className="h-3 w-1/2" />
      </div>
    </div>
  );
}

/** Силует колонки варіантів: заголовок, підпис і картка найкращого варіанта. */
function OptionsSkeleton() {
  return (
    <div className="flex flex-col gap-3 p-5">
      <Skeleton className="h-5 w-2/3" />
      <Skeleton className="h-3 w-1/2" />
      <Skeleton className="mt-2 h-36 w-full rounded-popover" />
      <Skeleton className="h-12 w-full rounded-popover" />
    </div>
  );
}

/**
 * Силует тієї самої форми, що й робоча панель (відсутні · пари · варіанти; на ноутбуці — рядок відсутніх над
 * двома колонками; на телефоні — список): поки не прийшли всі запити, панель не показується наполовину.
 */
export function WorkspaceSkeleton({ panes }: { panes: number }) {
  const lessons = (
    <div className="flex flex-col gap-1 p-3 pt-5">
      <div className="flex flex-col gap-1.5 px-3 pb-2">
        <Skeleton className="h-5 w-1/2" />
        <Skeleton className="h-3 w-2/3" />
      </div>
      {[0, 1, 2].map((key) => (
        <LessonRowSkeleton key={key} />
      ))}
    </div>
  );
  if (panes === 3) {
    return (
      <div aria-hidden="true" className="grid grid-cols-[28fr_32fr_40fr] rounded-panel border border-default bg-surface">
        <div className="flex flex-col gap-1 p-3">
          <Skeleton className="mx-3 mt-2 mb-1 h-4 w-20" />
          {[0, 1, 2, 3].map((key) => (
            <AbsenceRowSkeleton key={key} />
          ))}
        </div>
        <div className="border-s border-separator">{lessons}</div>
        <div className="border-s border-separator">
          <OptionsSkeleton />
        </div>
      </div>
    );
  }
  if (panes === 2) {
    return (
      <div aria-hidden="true" className="flex flex-col gap-4">
        <div className="flex gap-1.5">
          {[0, 1, 2, 3].map((key) => (
            <Skeleton key={key} className="h-9 w-28 rounded-full" />
          ))}
        </div>
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] rounded-panel border border-default bg-surface">
          {lessons}
          <div className="border-s border-separator">
            <OptionsSkeleton />
          </div>
        </div>
      </div>
    );
  }
  return (
    <div aria-hidden="true" className="rounded-panel border border-default bg-surface p-2 pt-3">
      {[0, 1, 2, 3].map((key) => (
        <AbsenceRowSkeleton key={key} />
      ))}
    </div>
  );
}
