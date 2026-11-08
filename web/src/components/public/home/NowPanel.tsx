import { useId } from 'react';
import { useBells, usePublicMeta } from '@/api/meta';
import { usePublicChanges } from '@/api/public';
import { NowIndicator } from '@/components/schedule';
import { ErrorState, ParityBadge, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { compareIsoDates, formatLongDate, formatWeekdayDate, isoWeekday, isWeekend } from '@/lib/date';
import { capitalize, weekdayName } from '@/lib/labels';
import { useHomeDay } from './useHomeDay';

/**
 * «Зараз»: яка пара йде й скільки до кінця, чисельник чи знаменник, номер тижня. У вихідні — який
 * наступний навчальний день і його парність (з сервера: враховує перевизначення тижнів).
 */
export function NowPanel({ className }: { className?: string }) {
  const headingId = useId();
  const meta = usePublicMeta();
  const home = useHomeDay();
  const bells = useBells(meta.data?.studyDay ? meta.data.today : null);
  const next = usePublicChanges(home.date);

  const frame = cn('flex flex-col gap-4 rounded-panel border border-default bg-surface p-4', className);

  if (meta.isPending) {
    return (
      <section aria-label="Зараз" className={frame}>
        <Skeleton className="h-5 w-32" />
        <Skeleton className="h-4 w-56" />
        <Skeleton className="h-10 w-full" />
      </section>
    );
  }
  if (meta.isError) {
    return (
      <section aria-label="Зараз" className={frame}>
        <ErrorState error={meta.error} size="sm" align="start" onRetry={() => void meta.refetch()} retrying={meta.isFetching} />
      </section>
    );
  }

  const data = meta.data;
  const semesterOver = compareIsoDates(data.today, data.semester.endsOn) > 0;
  const semesterAhead = compareIsoDates(data.today, data.semester.startsOn) < 0;

  return (
    <section aria-labelledby={headingId} className={frame}>
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h2 id={headingId} className="text-heading font-semibold text-primary">
            Зараз
          </h2>
          <p className="text-caption text-secondary">
            {formatWeekdayDate(data.today)} · {data.semester.name}
          </p>
        </div>
        {data.studyDay ? <ParityBadge parity={data.parity} weekNumber={data.weekNumber} size="sm" /> : null}
      </div>

      {data.studyDay ? (
        bells.data && bells.data.slots.length > 0 ? (
          <NowIndicator bells={bells.data.slots} date={data.today} className="pb-2" />
        ) : bells.isError ? (
          <p className="text-ui text-secondary">Дзвінки на сьогодні не завантажилися — «зараз» показати не можемо.</p>
        ) : (
          <Skeleton className="h-16 w-full" />
        )
      ) : semesterOver ? (
        <p className="text-ui text-secondary">{capitalize(data.semester.name)} завершився {formatLongDate(data.semester.endsOn)}.</p>
      ) : (
        <div className="flex flex-col gap-2">
          <p className="text-body font-semibold text-primary">
            {isWeekend(data.today) ? `Сьогодні ${weekdayName(isoWeekday(data.today))} — пар немає` : 'Сьогодні занять немає'}
          </p>
          <p className="flex flex-wrap items-center gap-2 text-ui text-secondary">
            {semesterAhead ? 'Заняття починаються' : 'Наступний навчальний день —'}{' '}
            <span className="font-semibold text-primary">{formatWeekdayDate(semesterAhead ? data.semester.startsOn : home.date)}</span>
            {!semesterAhead && next.data ? <ParityBadge parity={next.data.parity} size="sm" /> : null}
          </p>
        </div>
      )}
    </section>
  );
}
