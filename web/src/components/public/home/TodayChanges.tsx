import { ArrowRight, Star } from 'lucide-react';
import { useId } from 'react';
import { Link } from 'react-router';
import { usePublicChanges } from '@/api/public';
import { ErrorState, Skeleton, buttonClass } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstantTime, formatWeekdayDate } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { useFavoritesStore } from '@/stores/favorites';
import { changeGroupKey, isMyGroup } from '../changes/changes-filter';
import { useHomeDay } from './useHomeDay';

const CHANGE_FORMS = ['зміна', 'зміни', 'змін'] as const;

/**
 * Заміни дня одним поглядом: які групи зачеплені (свої — першими й помітно), номер розпорядження,
 * коли оновлено. Оновлюється кожні 2 хвилини, поки сторінка відкрита.
 */
export function TodayChanges({ className }: { className?: string }) {
  const headingId = useId();
  const home = useHomeDay();
  const changes = usePublicChanges(home.date, { refetchInterval: 2 * 60_000 });
  const favorites = useFavoritesStore((state) => state.favorites);
  const recent = useFavoritesStore((state) => state.recent);
  const mine = new Set([...favorites, ...recent].filter((t) => t.type === 'GROUP').map((t) => t.id));
  const title = home.isToday ? 'Заміни сьогодні' : `Заміни на ${formatWeekdayDate(home.date).toLocaleLowerCase('uk')}`;
  const changesLink = home.isToday ? '/changes' : `/changes?date=${home.date}`;

  let body;
  if (changes.isPending) {
    body = (
      <div className="flex flex-wrap gap-2" aria-hidden="true">
        {Array.from({ length: 5 }, (_, index) => (
          <Skeleton key={index} className="h-9 w-20" />
        ))}
      </div>
    );
  } else if (changes.isError) {
    body = <ErrorState error={changes.error} size="sm" align="start" onRetry={() => void changes.refetch()} retrying={changes.isFetching} />;
  } else if (changes.data.groups.length === 0) {
    body = (
      <p className="text-ui text-secondary">
        {changes.data.order
          ? 'У розпорядженні змін для груп немає — заняття за розкладом.'
          : 'Розпорядження ще не опубліковано. Поки що діє звичайний розклад.'}
      </p>
    );
  } else {
    const groups = [...changes.data.groups].sort((a, b) => Number(isMyGroup(mine, b.group.id)) - Number(isMyGroup(mine, a.group.id)));
    const ownCount = groups.filter((g) => isMyGroup(mine, g.group.id)).length;
    body = (
      <>
        <p className="text-ui text-secondary">
          Зміни у <span className="font-semibold text-primary">{countLabel(groups.length, ['групі', 'групах', 'групах'])}</span>
          {ownCount > 0 ? (
            <>
              , серед них <span className="font-semibold text-accent">{countLabel(ownCount, ['ваша', 'ваші', 'ваших'])}</span>
            </>
          ) : null}
          .
        </p>
        <ul className="flex flex-wrap gap-1.5">
          {groups.map(({ group, items }) => {
            const own = isMyGroup(mine, group.id);
            return (
              <li key={changeGroupKey(group)}>
                <Link
                  to={`/g/${encodeURIComponent(group.code)}?date=${changes.data.date}&view=day`}
                  aria-label={`${group.code}: ${countLabel(items.length, CHANGE_FORMS)}${own ? ', ваша група' : ''}`}
                  className={cn(
                    'inline-flex h-9 items-center gap-1.5 rounded-plate border px-2 transition-[border-color,background-color] duration-100',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                    own ? 'border-accent bg-selected text-accent hover:bg-selected-hover' : 'border-strong bg-surface text-primary hover:border-accent',
                  )}
                >
                  {own ? <Star aria-hidden="true" className="size-3.5 fill-current" /> : null}
                  <span className="font-condensed text-heading leading-none font-extrabold figures">{group.code}</span>
                  <span className="rounded-plate bg-change-subtle px-1 text-meta font-semibold text-change figures">{items.length}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      </>
    );
  }

  return (
    <section aria-labelledby={headingId} className={cn('flex flex-col gap-3 rounded-panel border border-default bg-surface p-4', className)}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h2 id={headingId} className="text-heading font-semibold text-primary">
          {title}
        </h2>
        {changes.data?.order ? (
          <span className="text-caption text-secondary figures">Розпорядження №{changes.data.order.number}</span>
        ) : null}
      </div>
      {body}
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-separator pt-3">
        <Link to={changesLink} className={buttonClass({ variant: 'link', className: 'gap-1.5' })}>
          Усі заміни
          <ArrowRight aria-hidden="true" className="size-4 rtl:rotate-180" />
        </Link>
        {changes.dataUpdatedAt > 0 ? (
          <span className="text-meta font-normal text-muted figures">
            оновлено о {formatInstantTime(new Date(changes.dataUpdatedAt).toISOString())}
          </span>
        ) : null}
      </div>
    </section>
  );
}
