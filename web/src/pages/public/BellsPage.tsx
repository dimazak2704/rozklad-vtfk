import { ChevronDown } from 'lucide-react';
import { usePublicMeta } from '@/api/meta';
import { useBellProfiles } from '@/api/public';
import type { Weekday } from '@/api/types';
import { BellsTable } from '@/components/public/bells/BellsTable';
import { bellColumns, usualBreakMinutes } from '@/components/public/bells/bells-columns';
import { HeaderParity } from '@/components/public/HeaderParity';
import { NowIndicator } from '@/components/schedule';
import { EmptyState, ErrorState, LoadingRegion, PageErrorScope, PageHeader, Skeleton } from '@/components/ui';
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

/**
 * Дзвінки: відлік і доріжка дня зверху (у навчальний день), під ними — таблиця профілю, що діє, зі
 * сьогоднішньою колонкою. Інші профілі — згорнуті, для довідки.
 */
export default function BellsPage() {
  const profiles = useBellProfiles();
  const meta = usePublicMeta();
  const today = meta.data?.today ?? todayInKyiv();
  const weekday = isoWeekday(today);
  // «сьогодні» — лише в навчальний день (у вихідні й канікули колонку не виділяємо)
  const studyToday = meta.data ? meta.data.studyDay : weekday <= 5;
  const active = profiles.data?.profiles?.find((p) => p.id === profiles.data.profile.id) ?? null;
  const others = profiles.data?.profiles?.filter((p) => p.id !== active?.id) ?? [];
  const usualBreak = active ? usualBreakMinutes(active) : null;
  const todaySlots =
    active && studyToday ? (bellColumns(active).find((c) => c.weekdays.includes(weekday as Weekday))?.slots ?? null) : null;

  return (
    <PageErrorScope queries={[profiles, meta]} title="Дзвінки зараз не завантажуються" className="max-w-4xl gap-8">
      <PageHeader
        size="headline"
        title="Дзвінки"
        meta={
          <>
            <span>{formatWeekdayDate(today)}</span>
            {studyToday && meta.data?.parity ? (
              <>
                <span aria-hidden="true">·</span>
                <HeaderParity parity={meta.data.parity} />
              </>
            ) : meta.data && !meta.data.studyDay ? (
              <span>· пар немає</span>
            ) : null}
          </>
        }
      />

      {todaySlots && todaySlots.length > 0 ? <NowIndicator variant="strip" bells={todaySlots} date={today} /> : null}

      {profiles.isPending ? (
        <BellsSkeleton />
      ) : profiles.isError ? (
        <ErrorState error={profiles.error} title="Не вдалося завантажити дзвінки" onRetry={() => void profiles.refetch()} retrying={profiles.isFetching} />
      ) : !active ? (
        <EmptyState title="Профіль дзвінків ще не налаштовано." description="Щойно диспетчер його налаштує, час пар зʼявиться тут." />
      ) : (
        <section aria-label={`Дзвінки: ${active.name}`} className="flex min-w-0 flex-col gap-3">
          <BellsTable profile={active} todayWeekday={studyToday ? weekday : null} live={studyToday} />
          <p className="max-w-prose text-caption text-muted">
            {usualBreak ? `Звичайна перерва між парами — ${usualBreak}\u00a0хв, довшу видно під парою. ` : null}
            Групи другої зміни починають пізніше — коли саме, видно в розкладі вашої групи.
          </p>
        </section>
      )}

      {others.length > 0 ? (
        <section aria-labelledby="other-profiles" className="flex flex-col gap-2">
          <h2 id="other-profiles" className="text-heading font-bold text-primary">
            Інші профілі
          </h2>
          <p className="text-ui text-secondary">Зараз не діють. Профіль перемикає диспетчер — тоді він стане основним.</p>
          {others.map((profile) => (
            <details key={profile.id} className="group border-b border-separator last:border-b-0">
              <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-2 rounded-control text-ui font-semibold text-primary focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus [&::-webkit-details-marker]:hidden">
                {profile.name}
                <ChevronDown aria-hidden="true" className="size-4 text-muted motion-safe:transition-transform motion-safe:duration-200 group-open:rotate-180" />
              </summary>
              <div className="pb-4">
                <BellsTable profile={profile} todayWeekday={null} />
              </div>
            </details>
          ))}
        </section>
      ) : null}
    </PageErrorScope>
  );
}
