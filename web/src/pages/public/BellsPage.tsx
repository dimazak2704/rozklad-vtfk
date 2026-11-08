import { ChevronRight } from 'lucide-react';
import { usePublicMeta } from '@/api/meta';
import { useBellProfiles } from '@/api/public';
import type { Weekday } from '@/api/types';
import { BellsTable } from '@/components/public/bells/BellsTable';
import { bellColumns } from '@/components/public/bells/bells-columns';
import { NowIndicator } from '@/components/schedule';
import { ErrorState, LoadingRegion, PageHeader, Skeleton } from '@/components/ui';
import { formatWeekdayDate, isoWeekday, todayInKyiv } from '@/lib/date';

function BellsSkeleton() {
  return (
    <LoadingRegion label="Завантажуємо дзвінки…">
      <div className="flex flex-col gap-2 rounded-panel border border-default bg-surface p-4">
        {Array.from({ length: 8 }, (_, index) => (
          <Skeleton key={index} className="h-9 w-full" />
        ))}
      </div>
    </LoadingRegion>
  );
}

/** Розклад дзвінків: профіль, що діє (сьогоднішній день виділено), живий відлік і інші профілі для довідки. */
export default function BellsPage() {
  const profiles = useBellProfiles();
  const meta = usePublicMeta();
  const today = meta.data?.today ?? todayInKyiv();
  const weekday = isoWeekday(today);
  // «сьогодні» — лише в навчальний день (у вихідні й канікули колонку не виділяємо)
  const studyToday = meta.data ? meta.data.studyDay : weekday <= 5;
  const active = profiles.data?.profiles?.find((p) => p.id === profiles.data.profile.id) ?? null;
  const others = profiles.data?.profiles?.filter((p) => p.id !== active?.id) ?? [];
  const todaySlots =
    active && studyToday ? (bellColumns(active).find((c) => c.weekdays.includes(weekday as Weekday))?.slots ?? null) : null;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Дзвінки"
        description={
          active
            ? `Діє профіль «${active.name}». Сьогодні ${formatWeekdayDate(today).toLocaleLowerCase('uk')}${studyToday ? '' : ' — пар немає'}.`
            : 'Час пар і перерв за профілем дзвінків, що діє.'
        }
      />

      {profiles.isPending ? (
        <BellsSkeleton />
      ) : profiles.isError ? (
        <ErrorState
          error={profiles.error}
          title="Не вдалося завантажити дзвінки"
          onRetry={() => void profiles.refetch()}
          retrying={profiles.isFetching}
          className="rounded-panel border border-default bg-surface"
        />
      ) : !active ? (
        <ErrorState title="Профіль дзвінків не знайдено" description="Сервер не повернув профіль, що діє. Спробуйте трохи згодом." className="rounded-panel border border-default bg-surface" />
      ) : (
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_24rem]">
          <section aria-label={`Дзвінки: ${active.name}`} className="flex min-w-0 flex-col gap-3">
            <BellsTable profile={active} todayWeekday={studyToday ? weekday : null} live={studyToday} />
            <p className="text-caption text-muted">
              Групи першої зміни починають з першої пари, другої зміни — з пізніших пар. Які саме пари у вашої групи, видно в її
              розкладі.
            </p>
          </section>

          <aside className="flex min-w-0 flex-col gap-6">
            {todaySlots && todaySlots.length > 0 ? (
              <section aria-label="Зараз" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
                <h2 className="text-heading font-semibold text-primary">Зараз</h2>
                <NowIndicator bells={todaySlots} date={today} className="pb-2" />
              </section>
            ) : null}

            {others.length > 0 ? (
              <section aria-labelledby="other-profiles" className="flex flex-col gap-3">
                <h2 id="other-profiles" className="text-heading font-semibold text-primary">
                  Інші профілі
                </h2>
                <p className="text-ui text-secondary">Зараз не діють. Профіль перемикає диспетчер, і тоді він зʼявиться вгорі.</p>
                {others.map((profile) => (
                  <details key={profile.id} className="group rounded-panel border border-default bg-surface">
                    <summary className="flex cursor-pointer list-none items-center gap-2 rounded-panel px-4 py-3 text-ui font-semibold text-primary hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus [&::-webkit-details-marker]:hidden">
                      <ChevronRight aria-hidden="true" className="size-4 text-muted motion-safe:transition-transform motion-safe:duration-150 group-open:rotate-90 rtl:rotate-180" />
                      {profile.name}
                    </summary>
                    <div className="px-2 pb-2">
                      <BellsTable profile={profile} todayWeekday={null} className="border-0" />
                    </div>
                  </details>
                ))}
              </section>
            ) : null}
          </aside>
        </div>
      )}
    </div>
  );
}
