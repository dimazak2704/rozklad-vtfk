import { useBells } from '@/api/meta';
import { usePublicSchedule } from '@/api/public';
import { DayList, GridNamesProvider } from '@/components/schedule';
import { EmptyState, ErrorState, useErrorCovered } from '@/components/ui';
import { MoreLink } from '../MoreLink';
import { ScheduleSkeleton } from '../schedule/ScheduleSkeleton';
import { useStalled } from '../useStalled';
import { homeDayWords, useHomeDay } from './useHomeDay';
import { useRememberedGroup } from './useRememberedGroup';

/**
 * «4КІ1 сьогодні» (увечері — «4КІ1 завтра») — розклад групи, яку людина відкривала останньою, одразу на головній. Без рамки:
 * рядки лежать на тлі сторінки (одна панель на екран — і на головній її немає взагалі).
 */
export function LastViewedDay() {
  const group = useRememberedGroup();
  const covered = useErrorCovered();
  const home = useHomeDay();
  const query = usePublicSchedule('GROUP', group?.id, home.date, home.date, { enabled: Boolean(group) });
  const bells = useBells(group ? home.date : null);
  const stall = useStalled([group ? query : null]);

  if (!group || (query.isError && !query.data && covered) || (query.isPending && stall)) return null;
  const day = query.data?.days[0];
  // після останнього дзвінка — уже наступний день: «4КІ1 завтра», «4КІ1 на понеділок, 16 листопада»
  const { when, dated } = homeDayWords(home);

  let body;
  if (day && day.lessons.length > 0) {
    body = (
      <GridNamesProvider days={query.data?.days}>
        <DayList day={day} bells={bells.data?.slots} perspective="group" today={home.today} showHeader={false} />
      </GridNamesProvider>
    );
  } else if (day) {
    body = <EmptyState size="sm" title={`У ${group.label} ${when}${dated ? ',' : ''} пар немає.`} className="py-2" />;
  } else if (query.isError) {
    body = (
      <ErrorState
        error={query.error}
        size="sm"
        align="start"
        description={`Розклад ${group.label} зараз не відкривається.`}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    );
  } else {
    body = <ScheduleSkeleton view="day" />;
  }

  return (
    <section aria-labelledby="last-viewed-heading" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="last-viewed-heading" className="font-display text-title font-semibold text-primary">
          <span className="figures">{group.label}</span> {when}
        </h2>
        <MoreLink to={`${group.url}?view=week`}>Тиждень</MoreLink>
      </div>
      {body}
    </section>
  );
}
