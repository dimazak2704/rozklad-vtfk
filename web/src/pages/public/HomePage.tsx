import { useBells, usePublicMeta } from '@/api/meta';
import { usePublicChanges, usePublicGroups, usePublicSchedule } from '@/api/public';
import { GroupsDirectory } from '@/components/public/home/GroupsDirectory';
import { LastViewedDay } from '@/components/public/home/LastViewedDay';
import { NowPanel } from '@/components/public/home/NowPanel';
import { StallNote } from '@/components/public/home/StallNote';
import { ScheduleFinder } from '@/components/public/home/ScheduleFinder';
import { TodayChanges } from '@/components/public/home/TodayChanges';
import { useHomeDay } from '@/components/public/home/useHomeDay';
import { useRememberedGroup } from '@/components/public/home/useRememberedGroup';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { PageErrorScope } from '@/components/ui';

/**
 * Головна — спокійний особистий календар: спершу «що зараз» (відлік і доріжка дня), потім пошук
 * розкладу, своя група на сьогодні, заміни одним реченням і всі групи під розкриттям.
 * Перший візит (своєї групи ще немає): під пошуком одразу список груп, праворуч — лише «Заміни».
 * Якщо сервер недоступний — одна помилка на сторінку з однією кнопкою, а не чотири однакові;
 * без мережі чи коли сервер довго мовчить — один спокійний рядок замість нескінченних силуетів.
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
  const queries = [meta, bells, groups, changes, group ? day : null];

  useDocumentTitle('Розклад ВТФК');

  return (
    <PageErrorScope queries={queries} title="Розклад зараз не завантажується" className="gap-8 md:gap-12">
      <StallNote queries={queries} />
      <NowPanel />

      <div className="grid items-start gap-x-12 gap-y-8 md:grid-cols-[minmax(0,1fr)_16rem] lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-8 md:gap-10">
          <section aria-labelledby="find-heading" className="flex flex-col gap-3">
            <h1 id="find-heading" className="font-display text-title font-semibold text-primary">
              Знайти розклад
            </h1>
            <ScheduleFinder autoFocus />
          </section>
          {group ? <LastViewedDay /> : <GroupsDirectory expanded />}
        </div>

        <aside aria-label={group ? 'Заміни й групи' : 'Заміни'} className="flex min-w-0 flex-col gap-8">
          <TodayChanges />
          {group ? <GroupsDirectory /> : null}
        </aside>
      </div>
    </PageErrorScope>
  );
}
