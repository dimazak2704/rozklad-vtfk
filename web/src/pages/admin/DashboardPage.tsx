import { ArrowLeftRight, FileText } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router';
import { useDailyLiveSync, useDashboard, useOrders } from '@/api/admin-daily';
import { usePublicMeta } from '@/api/meta';
import { useChangesWorkspace } from '@/components/admin-daily/changes/useChangesWorkspace';
import { ActivityFeed } from '@/components/admin-daily/dashboard/ActivityFeed';
import { AlertList } from '@/components/admin-daily/dashboard/AlertList';
import { MorningRoute } from '@/components/admin-daily/dashboard/MorningRoute';
import { SystemStatus } from '@/components/admin-daily/dashboard/SystemStatus';
import { UpcomingUncovered } from '@/components/admin-daily/dashboard/UpcomingUncovered';
import { useFreshSources } from '@/components/admin-daily/dashboard/useFreshSources';
import { buildFeed, LESSON_STATE } from '@/components/admin-daily/model';
import {
  Button,
  buttonClass,
  EmptyState,
  ErrorState,
  LoadingRegion,
  PageHeader,
  Panel,
  ParityBadge,
  Skeleton,
  SkeletonText,
  StatusDot,
} from '@/components/ui';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { formatWeekdayDate, isWeekend, relativeDayLabel, shiftStudyDay } from '@/lib/date';

/**
 * Панель диспетчера на робочу дату: маршрут ранку (відсутні → рішення → підтвердження → розпорядження),
 * що горить, найближчі пари без заміни, стрічка подій наживо і стан довколишніх систем.
 */
export default function DashboardPage() {
  const { date, today, setDate } = useWorkingDate();
  const meta = usePublicMeta();
  useDailyLiveSync();
  const dashboard = useDashboard(date);
  const workspace = useChangesWorkspace(date, 'day');
  const orders = useOrders({ from: date, to: date });
  const published = useMemo(
    () =>
      (orders.data?.items ?? [])
        .filter((o) => o.status === 'PUBLISHED')
        .sort((a, b) => (b.number ?? 0) - (a.number ?? 0))[0] ?? null,
    [orders.data],
  );

  const feed = useMemo(
    () => buildFeed([...workspace.changesById.values()], workspace.absences, orders.data?.items ?? [], date),
    [workspace.changesById, workspace.absences, orders.data, date],
  );

  // події, що прийшли наживо після відкриття сторінки, коротко підсвічуються
  const fresh = useFreshSources();

  const weekend = isWeekend(date);
  const nextStudyDay = shiftStudyDay(date, 1);
  const relative = relativeDayLabel(date, today);
  const needsAction = workspace.entries.filter((e) => LESSON_STATE[e.state].needsAction).length;
  const alerts = dashboard.data?.alerts ?? [];

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Панель диспетчера"
        description="Що відбувається з розкладом на робочу дату і що зробити далі."
        meta={
          <>
            <span className="text-ui font-semibold text-primary">
              {relative ? `${relative}, ` : ''}
              {formatWeekdayDate(date).toLocaleLowerCase('uk')}
            </span>
            {dashboard.data && !weekend ? <ParityBadge parity={dashboard.data.parity} weekNumber={meta.data?.today === date ? meta.data.weekNumber : undefined} size="sm" /> : null}
          </>
        }
        actions={
          <>
            <Link to={`/admin/orders?date=${date}`} className={buttonClass({ variant: 'secondary' })}>
              <FileText aria-hidden="true" className="size-4" />
              Розпорядження
            </Link>
            <Link to={`/admin/changes?date=${date}`} className={buttonClass({ variant: 'primary' })}>
              <ArrowLeftRight aria-hidden="true" className="size-4" />
              До замін
              {needsAction > 0 ? <span className="rounded-full bg-[var(--fill-surface)] px-1.5 text-meta text-accent figures">{needsAction}</span> : null}
            </Link>
          </>
        }
      />

      {weekend ? (
        <Panel>
          <EmptyState
            title={`${formatWeekdayDate(date)} — вихідний`}
            description="Пар немає, тож і замін теж. Підготуйте найближчий навчальний день: відсутності, повідомлені через бота, вже там."
            action={
              <Button variant="primary" onClick={() => setDate(nextStudyDay)}>
                До {formatWeekdayDate(nextStudyDay).toLocaleLowerCase('uk')}
              </Button>
            }
          />
        </Panel>
      ) : dashboard.isError && !dashboard.data ? (
        <Panel>
          <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
        </Panel>
      ) : (
        <Panel title="Маршрут ранку" description="Від відсутностей до опублікованого розпорядження. Натисніть зупинку, щоб перейти до роботи." flush>
          <MorningRoute dashboard={dashboard.data} published={published} date={date} />
        </Panel>
      )}

      {!weekend ? (
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
          <div className="contents lg:flex lg:min-w-0 lg:flex-col lg:gap-6">
            <Panel
              className="order-1"
              title="Що потребує уваги"
              description={alerts.length > 0 ? 'Від термінового до довідкового' : undefined}
              flush
            >
              {dashboard.isPending ? (
                <LoadingRegion label="Завантажуємо сповіщення…" className="flex flex-col gap-3 p-4">
                  <SkeletonText lines={2} />
                  <SkeletonText lines={2} />
                </LoadingRegion>
              ) : alerts.length === 0 ? (
                <EmptyState
                  size="sm"
                  title="Нічого не горить"
                  description="Нові відсутності, відмови й прострочені підтвердження зʼявляться тут."
                />
              ) : (
                <AlertList alerts={alerts} />
              )}
            </Panel>

            <Panel
              className="order-3"
              title="Стрічка подій"
              description="Відсутності, запити й відповіді в Telegram, публікації — наживо"
              actions={<StatusDot tone="live" pulse label="Оновлюється наживо" showLabel />}
              flush
            >
              {workspace.loading && feed.length === 0 ? (
                <LoadingRegion label="Завантажуємо події…" className="flex flex-col gap-3 p-4">
                  {[0, 1, 2, 3].map((key) => (
                    <Skeleton key={key} className="h-5 w-full" />
                  ))}
                </LoadingRegion>
              ) : workspace.error && feed.length === 0 ? (
                <ErrorState error={workspace.error} onRetry={workspace.retry} size="sm" />
              ) : feed.length === 0 ? (
                <EmptyState size="sm" title="Подій ще немає" description="Щойно хтось повідомить про відсутність чи відповість на запит, подія зʼявиться тут." />
              ) : (
                <ActivityFeed items={feed} fresh={fresh} />
              )}
            </Panel>
          </div>

          <div className="contents lg:flex lg:min-w-0 lg:flex-col lg:gap-6">
            <Panel
              className="order-2"
              title="Пари без рішення"
              description={relative === 'Сьогодні' ? 'За часом початку, з відліком' : 'За часом початку'}
              flush
              actions={
                needsAction > 0 ? (
                  <Link to={`/admin/changes?date=${date}&filter=uncovered`} className={buttonClass({ variant: 'ghost', size: 'sm' })}>
                    Усі {needsAction}
                  </Link>
                ) : null
              }
            >
              {workspace.loading && workspace.entries.length === 0 ? (
                <LoadingRegion label="Завантажуємо пари…" className="flex flex-col gap-3 p-4">
                  {[0, 1, 2].map((key) => (
                    <Skeleton key={key} className="h-10 w-full" />
                  ))}
                </LoadingRegion>
              ) : needsAction === 0 ? (
                <EmptyState
                  size="sm"
                  title={workspace.entries.length > 0 ? 'Усі пари закрито' : 'Замін не потрібно'}
                  description={
                    workspace.entries.length > 0
                      ? 'Лишилося дочекатися підтверджень і опублікувати розпорядження.'
                      : 'Відсутніх на цю дату немає.'
                  }
                />
              ) : (
                <UpcomingUncovered entries={workspace.entries} date={date} />
              )}
            </Panel>

            <Panel title="Сервіси" flush className="order-4">
              <SystemStatus dashboard={dashboard.data} />
            </Panel>
          </div>
        </div>
      ) : null}
    </div>
  );
}
