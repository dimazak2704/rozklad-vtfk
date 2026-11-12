import { ChevronRight } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router';
import { useDailyLiveSync, useDashboard, useOrders } from '@/api/admin-daily';
import { useBells } from '@/api/meta';
import { OfflineState } from '@/components/admin-daily/OfflineState';
import { waitsForNetwork } from '@/components/admin-daily/network';
import { useChangesWorkspace } from '@/components/admin-daily/changes/useChangesWorkspace';
import { ActivityFeed } from '@/components/admin-daily/dashboard/ActivityFeed';
import { NeedsList } from '@/components/admin-daily/dashboard/NeedsList';
import { useFreshSources } from '@/components/admin-daily/dashboard/useFreshSources';
import { useServiceAlerts } from '@/components/admin-daily/dashboard/useServiceAlerts';
import { buildFeed, LESSON_STATE } from '@/components/admin-daily/model';
import { ResolvedProgress } from '@/components/admin-daily/ResolvedProgress';
import { NowIndicator } from '@/components/schedule';
import {
  Button,
  EmptyState,
  ErrorState,
  LoadingRegion,
  PageErrorScope,
  PageHeader,
  Section,
  Skeleton,
  type QueryLike,
} from '@/components/ui';
import type { IsoDate } from '@/api/types';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useNow } from '@/hooks/useNow';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import {
  formatInstantTime,
  formatWeekdayDate,
  formatWeekdayDateAfterDo,
  formatWeekdayDateAfterNa,
  isWeekend,
  relativeDayLabel,
  shiftStudyDay,
} from '@/lib/date';
import { PAIR_FORMS, plural } from '@/lib/labels';

/** Тихе посилання «Усі заміни →» праворуч від заголовка розділу. */
function SectionLink({ to, children }: { to: string; children: string }) {
  return (
    <Link
      to={to}
      className="group/link -my-1 inline-flex min-h-9 items-center gap-0.5 rounded-control px-1 text-ui font-semibold text-accent underline-offset-4 hover-fine:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:-my-2.5 pointer-coarse:min-h-11"
    >
      {children}
      {/* та сама мова, що й «Усі заміни →» на головній: під мишею стрілка підʼїжджає на 3 px */}
      <ChevronRight
        aria-hidden="true"
        className="size-4 transition-transform duration-200 ease-out motion-reduce:transition-none rtl:rotate-180 pointer-fine:group-hover/link:translate-x-[3px]"
      />
    </Link>
  );
}

/**
 * Пари вже скінчилися: замість доріжки з восьми сірих смуг — один рядок, що дивиться вперед:
 * «Пари закінчилися о 18:10. Завтра 3 пари без заміни.» і перехід на наступний навчальний день.
 */
function DayOver({ endsAt, date, onNextDay }: { endsAt: string; date: IsoDate; onNextDay: (next: IsoDate) => void }) {
  const next = shiftStudyDay(date, 1);
  const tomorrow = useDashboard(next);
  const summary = tomorrow.isPlaceholderData ? undefined : tomorrow.data;
  const isTomorrow = relativeDayLabel(next, date) === 'Завтра';
  // «середу, 13 листопада» → «середу»: у кнопці вистачає дня тижня
  const weekday = formatWeekdayDateAfterNa(next).split(',')[0] ?? '';
  const onDay = isTomorrow ? 'На завтра' : `На ${weekday}`;
  let forward: string | null = null;
  if (summary) {
    const open = summary.uncoveredLessons;
    if (open > 0) forward = `${isTomorrow ? 'Завтра' : `На ${weekday}`} ${open} ${plural(open, PAIR_FORMS)} без заміни.`;
    else if (summary.absences > 0) forward = `${onDay} усі пари вже вирішено.`;
    else forward = `${onDay} відсутностей поки немає.`;
  }
  return (
    <section
      aria-label="Стан навчального дня"
      className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 rounded-[20px] bg-today-wash px-5 py-4 md:px-8"
    >
      <p className="text-body text-secondary">
        <span className="font-[650] text-primary">
          Пари закінчилися о <span className="figures">{endsAt}</span>.
        </span>
        {forward ? ` ${forward}` : null}
      </p>
      <Button variant="secondary" onClick={() => onNextDay(next)} iconEnd={<ChevronRight aria-hidden="true" className="size-4 rtl:rotate-180" />}>
        {isTomorrow ? 'Перейти на завтра' : `Перейти на ${weekday}`}
      </Button>
    </section>
  );
}

/**
 * Живий стан дня диспетчера: яка пара йде і скільки до дзвінка — лише на сьогодні й лише в навчальний день.
 * Після останньої пари — один рядок про наступний день замість доріжки.
 */
function DayNow({ date, onNextDay }: { date: IsoDate; onNextDay: (next: IsoDate) => void }) {
  const bells = useBells(date);
  const wide = useMediaQuery('(min-width: 768px)', true);
  const slots = bells.data?.slots;
  const { status } = useNow(slots && slots.length > 0 ? slots : null, { date });
  // місце героя тримаємо, поки дзвінки їдуть: інакше він вставиться згори й зсуне весь екран
  if (bells.isPending) {
    return (
      <LoadingRegion label="Завантажуємо стан дня…">
        <Skeleton className="h-[11.5rem] w-full rounded-[20px] md:h-48 md:rounded-hero" />
      </LoadingRegion>
    );
  }
  if (!slots || slots.length === 0) return null;
  if (status?.kind === 'after-day') return <DayOver endsAt={status.last.endsAt} date={date} onNextDay={onNextDay} />;
  return (
    <div className="rounded-[20px] bg-today-wash px-5 py-5 md:rounded-hero md:px-8 md:py-6">
      <NowIndicator bells={slots} date={date} variant={wide ? 'strip' : 'compact'} />
    </div>
  );
}

/**
 * «Сьогодні» диспетчера: живий стан дня, що потребує рішення (пари, сповіщення й зламані сервіси реченнями) і
 * останні дії. Заголовок — назва дня, як у меню й посиланнях назад («Сьогодні», «Завтра» чи дата).
 * Одне головне число — «Вирішено 3 з 7 пар»; дорога далі — посиланням у заголовку розділу, а не кнопкою над героєм.
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
  const serviceAlerts = useServiceAlerts(summary);

  const weekend = isWeekend(date);
  const nextStudyDay = shiftStudyDay(date, 1);
  const relative = relativeDayLabel(date, today);
  const needsAction = workspace.entries.filter((e) => LESSON_STATE[e.state].needsAction).length;
  const done = workspace.entries.length - needsAction;
  const draftRows = summary?.orderDraft?.itemCount ?? 0;

  const changesLink = `/admin/changes?date=${date}`;
  const ordersLink = `/admin/orders?date=${date}`;
  // наступний крок дня — тихе посилання в заголовку розділу, над тим, що воно підсумовує
  const next =
    weekend || workspace.loading ? null : needsAction === 0 && draftRows > 0 ? (
      <SectionLink to={ordersLink}>До розпорядження</SectionLink>
    ) : workspace.entries.length > 0 ? (
      <SectionLink to={changesLink}>Усі заміни</SectionLink>
    ) : null;

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
        title={relative ?? formatWeekdayDate(date)}
        meta={
          weekend ? null : workspace.loading && !workspace.offline && !workspace.error ? (
            // місце під «Вирішено 4 з 8 пар», поки пари й рішення їдуть: нічого не стрибає
            <span aria-hidden="true" className="flex h-[1.375rem] items-center">
              <Skeleton className="h-3 w-48" />
            </span>
          ) : workspace.entries.length > 0 ? (
            <ResolvedProgress done={done} total={workspace.entries.length} />
          ) : null
        }
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
          {relative === 'Сьогодні' ? <DayNow date={date} onNextDay={setDate} /> : null}

          {/* ≥1536: колонки фіксованої міри, а зайва ширина стає полем праворуч (DESIGN §13) */}
          <div className="grid items-start gap-x-12 gap-y-10 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)] 2xl:grid-cols-[minmax(0,45rem)_minmax(0,36rem)]">
            {/* рядок читається одним поглядом: ширина списку не більша за ~720 px навіть на 1920 */}
            <Section title="Що потребує рішення" actions={next} className="max-w-[45rem] gap-2">
              {offline && !summary ? (
                <OfflineState size="sm" />
              ) : !summary || (workspace.loading && workspace.entries.length === 0) ? (
                dashboard.isError || workspace.error ? (
                  <ErrorState error={dashboard.error ?? workspace.error} size="sm" inset />
                ) : (
                  // один рядок висотою спокійного речення (py-1 + рядок 24 px): здебільшого тут саме воно,
                  // тож три високі смуги не схлопуються в один рядок, коли дані приїдуть
                  <LoadingRegion label="Завантажуємо, що потребує рішення…" className="flex h-8 items-center">
                    <Skeleton className="h-4 w-3/4 max-w-md" />
                  </LoadingRegion>
                )
              ) : (
                <NeedsList
                  entries={workspace.entries}
                  alerts={summary.alerts}
                  serviceAlerts={serviceAlerts}
                  date={date}
                  pairsKnown={!workspace.error}
                  calm={calm}
                />
              )}
            </Section>

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
                  <p className="py-1 text-body text-secondary">Подій ще немає.</p>
                )
              ) : (
                <ActivityFeed items={feed} fresh={fresh} today={today} />
              )}
            </Section>
          </div>
        </PageErrorScope>
      )}
    </div>
  );
}
