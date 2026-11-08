import { Star } from 'lucide-react';
import { Link } from 'react-router';
import { usePublicGroups } from '@/api/public';
import { paths } from '@/app/routes';
import { ErrorState, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { useFavoritesStore } from '@/stores/favorites';

/**
 * Усі групи за курсами — як шапки курсових блоків друкованого розкладу. Для тих, хто не памʼятає код
 * або шукає групу друга; обране позначене зіркою.
 */
export function GroupsDirectory() {
  const groups = usePublicGroups();
  const favorites = useFavoritesStore((state) => state.favorites);
  const favoriteIds = new Set(favorites.filter((f) => f.type === 'GROUP').map((f) => f.id));

  let body;
  if (groups.isPending) {
    body = (
      <div className="flex flex-col gap-4" aria-hidden="true">
        {Array.from({ length: 4 }, (_, row) => (
          <div key={row} className="flex flex-wrap gap-1.5">
            {Array.from({ length: 10 }, (_, index) => (
              <Skeleton key={index} className="h-8 w-14" />
            ))}
          </div>
        ))}
      </div>
    );
  } else if (groups.isError) {
    body = <ErrorState error={groups.error} size="sm" align="start" onRetry={() => void groups.refetch()} retrying={groups.isFetching} />;
  } else {
    const courses = [...new Set(groups.data.map((g) => g.course))].sort((a, b) => a - b);
    body = (
      <dl className="flex flex-col divide-y divide-[var(--border-color-separator)]">
        {courses.map((course) => {
          const list = groups.data.filter((g) => g.course === course);
          return (
            <div key={course} className="grid gap-x-4 gap-y-2 py-3 first:pt-0 last:pb-0 sm:grid-cols-[5rem_minmax(0,1fr)]">
              <dt className="flex items-baseline gap-1.5 pt-1">
                <span className="font-condensed text-title leading-none font-bold text-primary figures">{course}</span>
                <span className="text-caption text-secondary">курс</span>
              </dt>
              <dd>
                <ul className="flex flex-wrap gap-1.5">
                  {list.map((group) => {
                    const favorite = favoriteIds.has(group.id);
                    return (
                      <li key={group.id}>
                        <Link
                          to={paths.group(group.code)}
                          title={`${group.specialty.name !== group.specialty.letters ? group.specialty.name : group.department}, ${group.shift} зміна`}
                          className={cn(
                            'inline-flex h-8 min-w-14 items-center justify-center gap-1 rounded-plate border bg-surface px-2',
                            'font-condensed text-ui leading-none font-extrabold tracking-[0.02em] figures',
                            'transition-[border-color,color,background-color] duration-100 hover:border-accent hover:bg-selected hover:text-accent',
                            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                            favorite ? 'border-accent text-accent' : 'border-strong text-primary',
                          )}
                        >
                          {favorite ? <Star aria-label="в обраному" className="size-3 fill-current" /> : null}
                          {group.code}
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              </dd>
            </div>
          );
        })}
      </dl>
    );
  }

  return (
    <section aria-labelledby="groups-heading" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <h2 id="groups-heading" className="text-title font-semibold text-primary">
          Усі групи
        </h2>
        {groups.data ? <span className="text-caption text-muted figures">{countLabel(groups.data.length, ['група', 'групи', 'груп'])}</span> : null}
      </div>
      <div className="rounded-panel border border-default bg-surface p-4">{body}</div>
    </section>
  );
}
