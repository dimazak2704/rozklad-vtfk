import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router';
import { useBells } from '@/api/meta';
import { usePublicSchedule } from '@/api/public';
import type { EntityType } from '@/api/types';
import { DayList } from '@/components/schedule';
import { ErrorState, Plate, buttonClass } from '@/components/ui';
import { formatWeekdayDate } from '@/lib/date';
import type { Perspective } from '@/lib/schedule';
import { useFavoritesStore } from '@/stores/favorites';
import { ScheduleSkeleton } from '../schedule/ScheduleSkeleton';
import { useHomeDay } from './useHomeDay';

const PERSPECTIVE: Record<EntityType, Perspective> = { GROUP: 'group', TEACHER: 'teacher', ROOM: 'room' };

/** Розклад останньої переглянутої групи (викладача, аудиторії) на сьогодні — одразу на головній. */
export function LastViewedDay() {
  const last = useFavoritesStore((state) => state.lastViewed);
  const home = useHomeDay();
  const query = usePublicSchedule(last?.type ?? 'GROUP', last?.id, home.date, home.date);
  const bells = useBells(last ? home.date : null);

  if (!last) return null;
  const day = query.data?.days[0];
  const when = home.isToday ? 'сьогодні' : formatWeekdayDate(home.date).toLocaleLowerCase('uk');

  return (
    <section aria-labelledby="last-viewed-heading" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <h2 id="last-viewed-heading" className="flex flex-wrap items-center gap-2.5 text-title font-semibold text-primary">
          {last.type === 'TEACHER' ? <span>{last.label}</span> : <Plate size="lg">{last.label}</Plate>}
          <span>{when}</span>
        </h2>
        <Link to={`${last.url}?view=week`} className={buttonClass({ variant: 'link', className: 'gap-1.5' })}>
          Розклад на тиждень
          <ArrowRight aria-hidden="true" className="size-4 rtl:rotate-180" />
        </Link>
      </div>
      <div className="rounded-panel border border-default bg-surface p-4">
        {day ? (
          <DayList day={day} bells={bells.data?.slots} perspective={PERSPECTIVE[last.type]} today={home.today} showHeader={false} />
        ) : query.isError ? (
          <ErrorState
            error={query.error}
            size="sm"
            align="start"
            description={`Розклад «${last.label}» зараз не відкривається.`}
            onRetry={() => void query.refetch()}
            retrying={query.isFetching}
          />
        ) : (
          <ScheduleSkeleton view="day" />
        )}
      </div>
    </section>
  );
}
