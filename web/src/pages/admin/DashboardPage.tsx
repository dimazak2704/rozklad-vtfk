import { ArrowLeftRight, FileText } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router';
import { useDailyLiveSync, useDashboard, useOrders } from '@/api/admin-daily';
import { useBells } from '@/api/meta';
import { OfflineState } from '@/components/admin-daily/OfflineState';
import { waitsForNetwork } from '@/components/admin-daily/network';
import { useChangesWorkspace } from '@/components/admin-daily/changes/useChangesWorkspace';
import { ActivityFeed } from '@/components/admin-daily/dashboard/ActivityFeed';
import { NeedsList } from '@/components/admin-daily/dashboard/NeedsList';
import { SystemStatus } from '@/components/admin-daily/dashboard/SystemStatus';
import { useFreshSources } from '@/components/admin-daily/dashboard/useFreshSources';
import { buildFeed, LESSON_STATE } from '@/components/admin-daily/model';
import { ResolvedProgress } from '@/components/admin-daily/ResolvedProgress';
import { NowIndicator } from '@/components/schedule';
import {
  Button,
  buttonClass,
  EmptyState,
  ErrorState,
  LoadingRegion,
  PageErrorScope,
  PageHeader,
  Section,
  Skeleton,
  type MoreMenuItem,
  type QueryLike,
} from '@/components/ui';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { formatInstantTime, formatWeekdayDate, formatWeekdayDateAfterDo, isWeekend, relativeDayLabel, shiftStudyDay } from '@/lib/date';

/** Живий стан дня диспетчера: яка пара йде і скільки до дзвінка — лише на сьогодні й лише в навчальний день. */
function DayNow({ date }: { date: string }) {
  const bells = useBells(date);
  const wide = useMediaQuery('(min-width: 768px)', true);
  const slots = bells.data?.slots ?? [];
  if (slots.length === 0) return null;
  return (
    <div className="rounded-[20px] bg-today-wash px-5 py-5 md:rounded-hero md:px-8 md:py-6">
      <NowIndicator bells={slots} date={date} variant={wide ? 'strip' : 'compact'} />
    </div>
  );
}

/**
 * «Сьогодні» диспетчера: живий стан дня, що потребує рішення (пари й сповіщення реченнями), останні дії
 * і стан сервісів. Одне головне число — «Вирішено 3 з 7 пар»; решта — словами.
 */
export default function DashboardPage() {
  const { date, today, setDate } = useWorkingDate();
  useDailyLiveSync();
  const dashboard = useDashboard(date);
  const workspace = useChangesWorkspace(date, 'day');
  const orders = useOrders({ from: date, to: date });
  // keepPreviousData тримає дані попередньої дати, поки нові не прийшли; без мережі вони не прийдуть,
  // тож показуємо лише дані саме цієї дати, а замість решти — «немає звʼязку»
  const summary = dashboard.isPlaceholderData ? undefined : dashboard.data;
  const dayOrders = orders.isPlaceholderData ? undefined : orders.data;
  const offline = waitsForNetwork(dashboard) || waitsForNetwork(orders) || workspace.offline;
  const published = useMemo(
    () =>
      (dayOrders?.items ?? [])
        .filter((o) => o.status === 'PUBLISHED')
        .sort((a, b) => (b.number ?? 0) - (a.number ?? 0))[0] ?? null,
    [dayOrders],
  );

  const feed = useMemo(
    () =>
      workspace.offline ? [] : buildFeed([...workspace.changesById.values()], workspace.absences, dayOrders?.items ?? [], date),
    [workspace.offline, workspace.changesById, workspace.absences, dayOrders, date],
  );

  // події, що прийшли наживо після відкриття сторінки, коротко підсвічуються
  const fresh = useFreshSources();

  const weekend = isWeekend(date);
  const nextStudyDay = shiftStudyDay(date, 1);
  const relative = relativeDayLabel(date, today);
  const title = relative === 'Сьогодні' || relative === 'Завтра' ? relative : formatWeekdayDate(date);
  const needsAction = workspace.entries.filter((e) => LESSON_STATE[e.state].needsAction).length;
  const done = workspace.entries.length - needsAction;
  const draftRows = summary?.orderDraft?.itemCount ?? 0;

  const changesLink = `/admin/changes?date=${date}`;
  const ordersLink = `/admin/orders?date=${date}`;
  // одна головна дія — наступний крок дня: закрити пари, а коли їх немає — опублікувати розпорядження
  const primary =
    weekend ? null : needsAction > 0 ? (
      <Link to={changesLink} className={buttonClass({ variant: 'primary' })}>
        <ArrowLeftRight aria-hidden="true" className="size-4" />
        До замін
      </Link>
    ) : draftRows > 0 ? (
      <Link to={ordersLink} className={buttonClass({ variant: 'primary' })}>
        <FileText aria-hidden="true" className="size-4" />
        Розпорядження
      </Link>
    ) : null;
  const more: MoreMenuItem[] = [
    { label: 'Заміни', icon: <ArrowLeftRight aria-hidden="true" />, to: changesLink },
    { label: 'Розпорядження', icon: <FileText aria-hidden="true" />, to: ordersLink },
  ];

  // одна помилка на сторінку: дані панелі, розпорядження дня й відсутності з рішеннями
  const workspaceQuery: QueryLike = {
    isError: Boolean(workspace.error),
    error: workspace.error,
    isFetching: workspace.loading,
    refetch: workspace.retry,
  };

  const calm =
    workspace.absences.length === 0
      ? `${relative ? `${relative} н` : 'Н'}іхто не відсутній — замін не потрібно.`
      : published && draftRows === 0
        ? `Усі пари вирішено, розпорядження №${published.number ?? '—'} опубліковане${published.publishedAt ? ` о ${formatInstantTime(published.publishedAt)}` : ''}.`
        : 'Усі пари вирішено. Лишилося опублікувати розпорядження.';

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title={title}
        meta={!weekend && workspace.entries.length > 0 ? <ResolvedProgress done={done} total={workspace.entries.length} /> : null}
        primaryAction={primary}
        more={more}
      />

      {weekend ? (
        <EmptyState
          title={`${formatWeekdayDate(date)} — вихідний, пар немає.`}
          description="Відсутності, повідомлені через бота, уже чекають на найближчий навчальний день."
          action={
            <Button variant="secondary" onClick={() => setDate(nextStudyDay)}>
              До {formatWeekdayDateAfterDo(nextStudyDay)}
            </Button>
          }
        />
      ) : (
        <PageErrorScope queries={[dashboard, orders, workspaceQuery]} className="gap-8">
          {relative === 'Сьогодні' ? <DayNow date={date} /> : null}

          <div className="grid items-start gap-x-12 gap-y-10 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
            <Section title="Що потребує рішення" className="gap-2">
              {offline && !summary ? (
                <OfflineState size="sm" />
              ) : !summary || (workspace.loading && workspace.entries.length === 0) ? (
                dashboard.isError || workspace.error ? (
                  <ErrorState error={dashboard.error ?? workspace.error} size="sm" inset />
                ) : (
                  <LoadingRegion label="Завантажуємо, що потребує рішення…" className="flex flex-col gap-2 px-3 py-2">
                    {[0, 1, 2].map((key) => (
                      <Skeleton key={key} className="h-11 w-full rounded-popover" />
                    ))}
                  </LoadingRegion>
                )
              ) : (
                <NeedsList entries={workspace.entries} alerts={summary.alerts} date={date} pairsKnown={!workspace.error} calm={calm} />
              )}
            </Section>

            <div className="flex min-w-0 flex-col gap-10">
              <Section title="Останні дії" className="gap-2">
                {workspace.offline ? (
                  <OfflineState size="sm" />
                ) : workspace.loading && feed.length === 0 ? (
                  <LoadingRegion label="Завантажуємо події…" className="flex flex-col gap-2 px-3 py-2">
                    {[0, 1, 2, 3].map((key) => (
                      <Skeleton key={key} className="h-5 w-full" />
                    ))}
                  </LoadingRegion>
                ) : feed.length === 0 ? (
                  workspace.error ? (
                    <ErrorState error={workspace.error} size="sm" inset />
                  ) : (
                    <p className="px-3 py-2 text-body text-secondary">Подій ще немає.</p>
                  )
                ) : (
                  <ActivityFeed items={feed} fresh={fresh} />
                )}
              </Section>

              <Section title="Сервіси" className="gap-2">
                <SystemStatus dashboard={summary} unavailable={dashboard.isError && !summary} />
              </Section>
            </div>
          </div>
        </PageErrorScope>
      )}
    </div>
  );
}
