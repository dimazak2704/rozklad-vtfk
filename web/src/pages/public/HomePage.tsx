import { useBells, usePublicMeta } from '@/api/meta';
import { usePublicChanges, usePublicGroups, usePublicSchedule } from '@/api/public';
import { GroupsDirectory } from '@/components/public/home/GroupsDirectory';
import { LastViewedDay } from '@/components/public/home/LastViewedDay';
import { NowPanel } from '@/components/public/home/NowPanel';
import { ScheduleFinder } from '@/components/public/home/ScheduleFinder';
import { TodayChanges } from '@/components/public/home/TodayChanges';
import { useHomeDay } from '@/components/public/home/useHomeDay';
import { useRememberedGroup } from '@/components/public/home/useRememberedGroup';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { PageErrorScope } from '@/components/ui';

/**
 * Головна — спокійний особистий календар: спершу «що зараз» (відлік і доріжка дня), потім пошук
 * розкладу, своя група на сьогодні, заміни одним реченням і всі групи під розкриттям.
 * Якщо сервер недоступний — одна помилка на сторінку з однією кнопкою, а не чотири однакові.
 */
export default function HomePage() {
  const meta = usePublicMeta();
  const home = useHomeDay();
  const group = useRememberedGroup();
  // ті самі запити, що й у блоках (React Query їх обʼєднує): потрібні лише для спільної помилки
  const bells = useBells(meta.data?.studyDay ? meta.data.today : null);
  const groups = usePublicGroups();
  const changes = usePublicChanges(home.date);
  const day = usePublicSchedule('GROUP', group?.id, home.date, home.date, { enabled: Boolean(group) });

  useDocumentTitle('Розклад ВТФК');

  return (
    <PageErrorScope queries={[meta, bells, groups, changes, group ? day : null]} title="Розклад зараз не завантажується" className="gap-8 md:gap-12">
      <NowPanel />

      <div className="grid items-start gap-x-12 gap-y-8 md:grid-cols-[minmax(0,1fr)_16rem] lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-8 md:gap-10">
          <section aria-labelledby="find-heading" className="flex flex-col gap-3">
            <h1 id="find-heading" className="font-display text-title font-semibold text-primary">
              Знайти розклад
            </h1>
            <ScheduleFinder autoFocus />
          </section>
          <LastViewedDay />
        </div>

        <aside aria-label="Заміни й групи" className="flex min-w-0 flex-col gap-8">
          <TodayChanges />
          <GroupsDirectory />
        </aside>
      </div>
    </PageErrorScope>
  );
}
