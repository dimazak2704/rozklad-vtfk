import { useId } from 'react';
import { Link } from 'react-router';
import { usePublicChanges } from '@/api/public';
import { ErrorState, Skeleton, useErrorCovered } from '@/components/ui';
import { cn } from '@/lib/cn';
import { capitalize, countLabel } from '@/lib/labels';
import { useFavoritesStore } from '@/stores/favorites';
import { GroupHint } from '../GroupHint';
import { MoreLink } from '../MoreLink';
import { useStalled } from '../useStalled';
import { isMyGroup } from '../changes/changes-filter';
import { homeDayWords, useHomeDay } from './useHomeDay';

const CHANGE_FORMS = ['заміна', 'заміни', 'замін'] as const;
const GROUP_FORMS = ['групі', 'групах', 'групах'] as const;

/**
 * «Заміни» на головній — одне речення, а не стіна табличок: скільки замін і чи є серед них своя група.
 * Оновлюється кожні 2 хвилини, поки сторінка відкрита.
 */
export function TodayChanges({ className }: { className?: string }) {
  const headingId = useId();
  const covered = useErrorCovered();
  const home = useHomeDay();
  const changes = usePublicChanges(home.date, { refetchInterval: 2 * 60_000 });
  const favorites = useFavoritesStore((state) => state.favorites);
  const recent = useFavoritesStore((state) => state.recent);
  const mine = new Set([...favorites, ...recent].filter((t) => t.type === 'GROUP').map((t) => t.id));
  // «Сьогодні» / «Завтра» / «На пʼятницю, 13 листопада,» — після дати з числом речення йде через кому
  const words = homeDayWords(home);
  const day = `${capitalize(words.when)}${words.dated ? ',' : ''}`;
  const onDay = `${words.onDay}${words.dated ? ',' : ''}`;
  const changesLink = home.isToday ? '/changes' : `/changes?date=${home.date}`;
  const stall = useStalled([changes]);

  // спільна помилка сторінки вже сказала, що сталося: блок без даних просто не показуємо
  if (changes.isError && !changes.data && covered) return null;
  // немає мережі чи сервер мовчить — про це вже сказав рядок сторінки; порожній силует не тримаємо
  if (changes.isPending && stall) return null;
  let body;
  if (changes.isPending) {
    body = (
      // рядки висотою з рядок тексту (24 px): у широкій колонці речення вміщається в один, у вузькій — у два,
      // тож «Усі групи» під ним не підскакують, коли відповідь приходить
      <div className="flex flex-col" aria-hidden="true">
        <span className="flex h-6 items-center">
          <Skeleton className="h-4 w-full lg:w-4/5" />
        </span>
        <span className="flex h-6 items-center lg:hidden">
          <Skeleton className="h-4 w-2/3" />
        </span>
      </div>
    );
  } else if (changes.isError) {
    body = <ErrorState error={changes.error} size="sm" align="start" onRetry={() => void changes.refetch()} retrying={changes.isFetching} />;
  } else if (changes.data.groups.length === 0) {
    body = (
      <p className="text-body text-secondary">
        {changes.data.order
          ? `${day} замін немає — заняття за розкладом.`
          : changes.data.studyDay
            ? `Розпорядження ${onDay} ще не опубліковано. Поки що діє звичайний розклад.`
            : `${day} занять немає.`}
      </p>
    );
  } else {
    const groups = changes.data.groups;
    const rows = groups.reduce((sum, g) => sum + g.items.length, 0);
    const own = groups.filter((g) => isMyGroup(mine, g.group.id));
    body = (
      <p className="text-body text-secondary">
        {day} {countLabel(rows, CHANGE_FORMS)} у {countLabel(groups.length, GROUP_FORMS)}
        {own.length > 0 ? (
          <>
            , серед них у{' '}
            {own.map(({ group }, index) => (
              <span key={group.code}>
                {index > 0 ? ', ' : null}
                <GroupHint code={group.code}>
                  <Link
                    to={`/g/${encodeURIComponent(group.code)}?date=${changes.data.date}&view=day`}
                    className="rounded-tag font-[650] text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
                  >
                    <span className="u-line">{group.code}</span>
                  </Link>
                </GroupHint>
              </span>
            ))}
          </>
        ) : null}
        .
      </p>
    );
  }

  return (
    <section aria-labelledby={headingId} className={cn('flex flex-col gap-2', className)}>
      <h2 id={headingId} className="font-display text-title font-semibold text-primary">
        Заміни
      </h2>
      {body}
      <MoreLink to={changesLink}>Усі заміни</MoreLink>
    </section>
  );
}
