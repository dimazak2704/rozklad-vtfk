import { ArrowLeft, Send } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import {
  useChanges,
  useDailyLiveSync,
  useDashboard,
  useNextOrderNumber,
  useOrder,
  useOrderDraft,
  useOrders,
  usePublishOrder,
} from '@/api/admin-daily';
import { usePublicMeta } from '@/api/meta';
import { qk } from '@/api/queryKeys';
import type { ChangeDto, IsoDate, OrderDto, Uuid } from '@/api/types';
import { useFreshSources } from '@/components/admin-daily/dashboard/useFreshSources';
import { DeliverySummary } from '@/components/admin-daily/orders/DeliverySummary';
import { NotesEditor } from '@/components/admin-daily/orders/NotesEditor';
import { OrderFiles } from '@/components/admin-daily/orders/OrderFiles';
import { OrderSheet } from '@/components/admin-daily/orders/OrderSheet';
import { OrdersArchive } from '@/components/admin-daily/orders/OrdersArchive';
import { PublishDialog } from '@/components/admin-daily/orders/PublishDialog';
import { WeekStrip } from '@/components/admin-daily/orders/WeekStrip';
import {
  Badge,
  Button,
  buttonClass,
  Chip,
  EmptyState,
  ErrorState,
  LoadingRegion,
  PageHeader,
  Panel,
  Section,
  Skeleton,
  notify,
} from '@/components/ui';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { compareIsoDates, formatInstant, formatLongDate, formatWeekdayDate, isWeekend, shiftStudyDay } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { useRegisterCommands } from '@/stores/commands';

const ROWS = ['рядок', 'рядки', 'рядків'] as const;

function SheetSkeleton() {
  return (
    <LoadingRegion label="Складаємо розпорядження…" className="mx-auto w-full max-w-[52rem]">
      <div className="flex aspect-[210/297] flex-col gap-3 border border-default bg-surface p-[7%]">
        <Skeleton className="mx-auto h-5 w-1/2" />
        <Skeleton className="mx-auto h-4 w-2/3" />
        <Skeleton className="mx-auto mb-4 h-4 w-1/3" />
        {[0, 1, 2, 3, 4, 5].map((key) => (
          <Skeleton key={key} className="h-6 w-full" />
        ))}
      </div>
    </LoadingRegion>
  );
}

/**
 * Розпорядження: чернетка на робочу дату у форматі коледжу, примітки, публікація з номером, файли й
 * розсилка після публікації, архів з пошуком.
 */
export default function OrdersPage() {
  const { date, setDate } = useWorkingDate();
  const meta = usePublicMeta();
  useDailyLiveSync();
  const fresh = useFreshSources();
  const semester = meta.data ? { startsOn: meta.data.semester.startsOn, endsOn: meta.data.semester.endsOn } : null;
  const inSemester = !semester || (compareIsoDates(date, semester.startsOn) >= 0 && compareIsoDates(date, semester.endsOn) <= 0);
  const weekend = isWeekend(date);

  const draft = useOrderDraft(date, !weekend && inSemester);
  const queryClient = useQueryClient();
  const draftSignature = draft.data ? `${draft.data.id}:${draft.data.items.length + draft.data.roomChanges.length}` : null;
  // сервер будує чернетку під час запиту — списки (тиждень, архів) мають побачити її одразу
  useEffect(() => {
    if (draftSignature) void queryClient.invalidateQueries({ queryKey: [...qk.admin.orders.all, 'list'] });
  }, [draftSignature, queryClient]);
  const dayOrders = useOrders({ from: date, to: date, status: 'PUBLISHED' });
  const publishedOfDay = useMemo(
    () => [...(dayOrders.data?.items ?? [])].sort((a, b) => (a.number ?? 0) - (b.number ?? 0)),
    [dayOrders.data],
  );
  // документ з архіву (чи щойно опублікований), відкритий на цій робочій даті
  const [viewed, setViewed] = useState<{ id: Uuid; forDate: IsoDate } | null>(null);
  const viewedId = viewed && viewed.forDate === date ? viewed.id : null;
  const draftHasContent = Boolean(draft.data && (draft.data.items.length + draft.data.roomChanges.length + draft.data.notes.length > 0));
  const defaultPublishedId = !draftHasContent && publishedOfDay.length > 0 ? (publishedOfDay.at(-1)?.id ?? null) : null;
  const detailId = viewedId ?? defaultPublishedId;
  const detail = useOrder(detailId);
  const order: OrderDto | undefined = detailId ? detail.data : draft.data;
  const parent = useOrder(order?.parentId ?? null);
  const changes = useChanges(order?.date ?? date);
  const changesById = useMemo(() => new Map<Uuid, ChangeDto>((changes.data ?? []).map((c) => [c.id, c])), [changes.data]);
  // відмова рахується, лише поки на пару не обрано нового кандидата — це знає сервер (панель на дату)
  const dayDashboard = useDashboard(order?.date ?? date);
  const pending = dayDashboard.data?.pending ?? 0;
  const declined = dayDashboard.data?.declined ?? 0;
  const next = useNextOrderNumber(semester ?? undefined);
  const publish = usePublishOrder();
  const [publishOpen, setPublishOpen] = useState(false);

  const isDraft = order?.status === 'DRAFT';
  const rows = order ? order.items.length + order.roomChanges.length : 0;
  const canPublish = Boolean(isDraft && order && rows + order.notes.length > 0);
  const archived = Boolean(viewedId && order && order.date !== date);

  const runPublish = async () => {
    if (!order) throw new Error('Немає чернетки');
    const published = await publish.mutateAsync(order.id);
    setViewed({ id: published.id, forDate: date });
    notify.success(`${published.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження'} №${published.number ?? '—'} опубліковано`, {
      description: 'Зміни внесено в розклад, групи отримують повідомлення в Telegram.',
    });
    return published;
  };

  useRegisterCommands(
    canPublish ? [{ id: 'publish-order', label: 'Опублікувати розпорядження', group: 'Розпорядження', icon: Send, perform: () => setPublishOpen(true) }] : [],
  );

  const selectDay = (day: IsoDate) => {
    setViewed(null);
    setDate(day);
  };

  const status = !order ? null : order.status === 'PUBLISHED' ? (
    <Badge tone="accent">
      Опубліковано №{order.number ?? '—'}
      {publishedOfDay.find((o) => o.id === order.id)?.publishedAt ? ` · ${formatInstant(publishedOfDay.find((o) => o.id === order.id)?.publishedAt ?? '')}` : ''}
    </Badge>
  ) : (
    <Badge tone="warning">{order.kind === 'AMENDMENT' ? 'Чернетка доповнення' : 'Чернетка'}</Badge>
  );

  let main;
  if (weekend && !archived) {
    main = (
      <Panel>
        <EmptyState
          title={`${formatWeekdayDate(date)} — вихідний`}
          description="На вихідні розпорядження не складають. Перейдіть до найближчого навчального дня або відкрийте документ з архіву нижче."
          action={
            <Button variant="primary" onClick={() => selectDay(shiftStudyDay(date, 1))}>
              До {formatWeekdayDate(shiftStudyDay(date, 1)).toLocaleLowerCase('uk')}
            </Button>
          }
        />
      </Panel>
    );
  } else if (!order) {
    const error = detailId ? detail.error : draft.error;
    main = error ? (
      <Panel>
        <ErrorState error={error} onRetry={() => void (detailId ? detail.refetch() : draft.refetch())} />
      </Panel>
    ) : (
      <SheetSkeleton />
    );
  } else {
    main = (
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_19rem] 2xl:grid-cols-[minmax(0,1fr)_21rem]">
        <div className="flex min-w-0 flex-col gap-3">
          {publishedOfDay.length > 0 && !archived ? (
            <div role="group" aria-label="Документи на цю дату" className="flex flex-wrap gap-1.5">
              {publishedOfDay.map((item) => (
                <Chip key={item.id} selected={order.id === item.id} onToggle={() => setViewed({ id: item.id, forDate: date })}>
                  {item.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження'} №{item.number ?? '—'}
                </Chip>
              ))}
              {draft.data ? (
                <Chip selected={isDraft} onToggle={() => setViewed(null)} count={draft.data.items.length + draft.data.roomChanges.length}>
                  {draft.data.kind === 'AMENDMENT' ? 'Чернетка доповнення' : 'Чернетка'}
                </Chip>
              ) : null}
            </div>
          ) : null}
          {archived ? (
            <p className="flex flex-wrap items-center gap-2 text-ui text-secondary">
              Архів: документ на {formatLongDate(order.date)}.
              <Button size="sm" variant="ghost" iconStart={<ArrowLeft aria-hidden="true" className="size-4" />} onClick={() => setViewed(null)}>
                До розпорядження на {formatWeekdayDate(date).toLocaleLowerCase('uk')}
              </Button>
            </p>
          ) : null}
          <div className="rounded-panel bg-sunken p-3 sm:p-5">
            <OrderSheet order={order} parentNumber={parent.data?.number ?? null} changes={changesById} fresh={fresh} />
          </div>
        </div>

        <aside aria-label="Дії з розпорядженням" className="order-first flex min-w-0 flex-col gap-4 lg:sticky lg:order-none lg:top-[calc(var(--sticky-top)+1rem)]">
          <Panel title={isDraft ? 'Перед публікацією' : 'Після публікації'} headingLevel={2}>
            <div className="flex flex-col gap-3 text-ui">
              <p className="text-secondary">
                {countLabel(rows, ROWS)}
                {order.notes.length > 0 ? ` · приміток: ${order.notes.length}` : ''}
              </p>
              {isDraft && (pending > 0 || declined > 0) ? (
                <p className="rounded-control border border-change bg-change-subtle px-3 py-2 text-caption text-change">
                  {pending > 0 ? `Чекають підтвердження: ${pending}. ` : ''}
                  {declined > 0 ? `Відмов без нового кандидата: ${declined}. ` : ''}
                  У цей документ не потраплять.{' '}
                  <Link to={`/admin/changes?date=${order.date}&filter=${pending > 0 ? 'pending' : 'declined'}`} className="font-semibold underline underline-offset-4">
                    До замін
                  </Link>
                </p>
              ) : null}
              {isDraft ? (
                <Button
                  variant="primary"
                  disabled={!canPublish}
                  onClick={() => setPublishOpen(true)}
                  iconStart={<Send aria-hidden="true" className="size-4" />}
                  className="w-full"
                >
                  {next.nextNumber !== null ? `Опублікувати №${next.nextNumber}` : 'Опублікувати'}
                </Button>
              ) : (
                <OrderFiles order={order} />
              )}
              {isDraft && !canPublish ? (
                <p className="text-caption text-muted">Публікувати нічого: підтверджених змін і приміток немає.</p>
              ) : null}
            </div>
          </Panel>
          {isDraft ? (
            <Panel title="Примітки" description="Друкуються жирним під таблицею" headingLevel={2}>
              <NotesEditor key={`${order.id}:${order.notes.join('\n')}`} order={order} />
            </Panel>
          ) : (
            <Panel title="Розсилка в Telegram" headingLevel={2}>
              <DeliverySummary orderId={order.id} />
            </Panel>
          )}
        </aside>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Розпорядження"
        description="Чернетка складається з підтверджених замін сама. Перевірте, додайте примітки й опублікуйте — номер присвоїться автоматично."
        meta={
          <>
            <span className="text-ui font-semibold text-primary">на {formatWeekdayDate(date).toLocaleLowerCase('uk')}</span>
            {status}
          </>
        }
        actions={
          <Link to={`/admin/changes?date=${date}`} className={buttonClass({ variant: 'secondary' })}>
            До замін
          </Link>
        }
      />

      <WeekStrip date={date} onSelect={selectDay} />

      {main}

      <Section id="archive" title="Архів" description="Усі опубліковані розпорядження семестру з пошуком.">
        <OrdersArchive
          semester={semester}
          selectedId={viewedId}
          onOpen={(row) => {
            setViewed({ id: row.id, forDate: date });
            window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
          }}
        />
      </Section>

      {order && isDraft ? (
        <PublishDialog
          open={publishOpen}
          onOpenChange={setPublishOpen}
          order={order}
          parentNumber={parent.data?.number ?? null}
          nextNumber={next.nextNumber}
          pending={pending}
          declined={declined}
          onPublish={runPublish}
        />
      ) : null}
    </div>
  );
}
