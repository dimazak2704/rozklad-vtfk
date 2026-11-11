import { ArrowLeft, ArrowLeftRight, Send } from 'lucide-react';
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
import { waitsForNetwork } from '@/components/admin-daily/network';
import { OfflineState } from '@/components/admin-daily/OfflineState';
import { DeliverySummary } from '@/components/admin-daily/orders/DeliverySummary';
import { NotesEditor } from '@/components/admin-daily/orders/NotesEditor';
import { useOrderFiles } from '@/components/admin-daily/orders/OrderFiles';
import { OrderRows } from '@/components/admin-daily/orders/OrderRows';
import { OrderSheet } from '@/components/admin-daily/orders/OrderSheet';
import { OrdersArchive } from '@/components/admin-daily/orders/OrdersArchive';
import { PublishDialog } from '@/components/admin-daily/orders/PublishDialog';
import { WeekStrip } from '@/components/admin-daily/orders/WeekStrip';
import {
  Button,
  buttonClass,
  Chip,
  EmptyState,
  LoadingRegion,
  PageErrorScope,
  PageHeader,
  Section,
  Skeleton,
  notify,
  type MoreMenuItem,
  type QueryLike,
} from '@/components/ui';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { compareIsoDates, formatInstant, formatLongDate, formatWeekdayDate, formatWeekdayDateAfterDo, formatWeekdayDateAfterNa, isWeekend, shiftStudyDay } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { useRegisterCommands } from '@/stores/commands';

const ROWS = ['рядок', 'рядки', 'рядків'] as const;
const CHANGES = ['заміна', 'заміни', 'замін'] as const;

function SheetSkeleton() {
  return (
    <LoadingRegion label="Складаємо розпорядження…" className="w-full max-w-[52rem]">
      <div className="flex flex-col gap-3 rounded-panel bg-sunken p-6">
        <Skeleton className="mx-auto h-5 w-1/2" />
        <Skeleton className="mx-auto mb-4 h-4 w-1/3" />
        {[0, 1, 2, 3].map((key) => (
          <Skeleton key={key} className="h-6 w-full" />
        ))}
      </div>
    </LoadingRegion>
  );
}

/**
 * Розпорядження: чернетка на робочу дату у форматі коледжу, примітки, публікація з номером, файли й
 * розсилка після публікації, архів з пошуком. На телефоні аркуш A4 нечитабельний — там ті самі рядки списком.
 */
export default function OrdersPage() {
  const { date, setDate } = useWorkingDate();
  const meta = usePublicMeta();
  useDailyLiveSync();
  const fresh = useFreshSources();
  const wide = useMediaQuery('(min-width: 640px)', true);
  // без семестру (null) межі не перевіряємо в клієнті: сервер сам відповість 422 для дати поза семестром
  const semester = meta.data?.semester ?? null;
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
  // список попередньої дати (keepPreviousData) — не документи цієї дати
  const dayOrdersData = dayOrders.isPlaceholderData ? undefined : dayOrders.data;
  const publishedOfDay = useMemo(
    () => [...(dayOrdersData?.items ?? [])].sort((a, b) => (a.number ?? 0) - (b.number ?? 0)),
    [dayOrdersData],
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
  const files = useOrderFiles(order);

  const isDraft = order?.status === 'DRAFT';
  const rows = order ? order.items.length + order.roomChanges.length : 0;
  const empty = Boolean(order && rows + order.notes.length === 0);
  const canPublish = Boolean(isDraft && order && !empty);
  const archived = Boolean(viewedId && order && order.date !== date);

  const runPublish = async () => {
    if (!order) throw new Error('Немає чернетки');
    const published = await publish.mutateAsync(order.id);
    setViewed({ id: published.id, forDate: date });
    notify.success(`${published.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження'} №${published.number ?? '—'} опубліковано`);
    return published;
  };

  useRegisterCommands(
    canPublish ? [{ id: 'publish-order', label: 'Опублікувати розпорядження', group: 'Розпорядження', icon: Send, perform: () => setPublishOpen(true) }] : [],
  );

  const selectDay = (day: IsoDate) => {
    setViewed(null);
    setDate(day);
  };

  const changesLink = `/admin/changes?date=${order?.date ?? date}`;
  const publishedAt = order?.publishedAt ?? publishedOfDay.find((o) => o.id === order?.id)?.publishedAt ?? null;
  // один рядок контексту: що це за документ і в якому він стані
  const status = !order
    ? null
    : order.status === 'PUBLISHED'
      ? `№${order.number ?? '—'} на ${formatWeekdayDateAfterNa(order.date)}${publishedAt ? ` · опубліковано ${formatInstant(publishedAt)}` : ''}`
      : `${order.kind === 'AMENDMENT' ? 'Чернетка доповнення' : 'Чернетка'} на ${formatWeekdayDateAfterNa(order.date)}${rows > 0 ? ` · ${countLabel(rows, ROWS)}` : ''}`;

  const primary = canPublish ? (
    <Button variant="primary" onClick={() => setPublishOpen(true)} iconStart={<Send aria-hidden="true" className="size-4" />}>
      {next.nextNumber !== null ? `Опублікувати №${next.nextNumber}` : 'Опублікувати'}
    </Button>
  ) : (
    files.pdfButton
  );
  const more: MoreMenuItem[] = [
    ...files.menu,
    { label: 'До замін', icon: <ArrowLeftRight aria-hidden="true" />, to: changesLink, separatorBefore: files.menu.length > 0 },
  ];

  // одна помилка на сторінку: чернетка чи документ дати; повтор перечитує всі списки розпоряджень (і архів)
  const failed = [detailId ? detail : draft, dayOrders].find((query) => query.isError);
  const pageQuery: QueryLike = {
    isError: Boolean(failed),
    error: failed?.error,
    isFetching: draft.isFetching || detail.isFetching || dayOrders.isFetching,
    refetch: () => void queryClient.refetchQueries({ queryKey: qk.admin.orders.all }),
  };

  const waiting =
    isDraft && (pending > 0 || declined > 0) ? (
      <p className="text-ui text-change">
        {pending > 0 ? `${countLabel(pending, CHANGES)} ще ${pending === 1 ? 'чекає' : 'чекають'} підтвердження` : ''}
        {pending > 0 && declined > 0 ? ', а ' : ''}
        {declined > 0 ? `${declined} без нового кандидата після відмови` : ''} — у цей документ не потраплять.{' '}
        <Link to={`${changesLink}`} className="font-semibold underline underline-offset-4">
          До замін
        </Link>
      </p>
    ) : null;

  let main;
  if (weekend && !archived) {
    main = (
      <EmptyState
        title={`${formatWeekdayDate(date)} — вихідний, розпорядження не складають.`}
        action={
          <Button variant="secondary" onClick={() => selectDay(shiftStudyDay(date, 1))}>
            До {formatWeekdayDateAfterDo(shiftStudyDay(date, 1))}
          </Button>
        }
      />
    );
  } else if (!order) {
    const error = detailId ? detail.error : draft.error;
    const offline = detailId ? waitsForNetwork(detail) : waitsForNetwork(draft) || waitsForNetwork(dayOrders);
    // помилку показує одна смуга над сторінкою (PageErrorScope), тут — нічого
    main = error ? null : offline ? <OfflineState /> : <SheetSkeleton />;
  } else if (isDraft && empty && publishedOfDay.length === 0) {
    // порожня чернетка — речення, а не порожній аркуш A4 з водяним знаком
    main = (
      <EmptyState
        title={`На ${formatWeekdayDateAfterNa(order.date)} підтверджених змін ще немає.`}
        description={waiting ?? 'Щойно викладач підтвердить заміну, рядок зʼявиться в чернетці сам.'}
        action={
          waiting ? null : (
            <Link to={changesLink} className={buttonClass({ variant: 'ghost', className: '-ms-3' })}>
              До замін
            </Link>
          )
        }
      />
    );
  } else {
    main = (
      <div className="grid items-start gap-x-8 gap-y-8 lg:grid-cols-[minmax(0,1fr)_18rem] 2xl:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-4">
          {publishedOfDay.length > 0 && !archived ? (
            <div role="group" aria-label="Документи на цю дату" className="flex flex-wrap gap-1.5">
              {publishedOfDay.map((item) => (
                <Chip key={item.id} selected={order.id === item.id} onToggle={() => setViewed({ id: item.id, forDate: date })}>
                  {item.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження'} №{item.number ?? '—'}
                </Chip>
              ))}
              {draft.data && draftHasContent ? (
                <Chip selected={isDraft} onToggle={() => setViewed(null)}>
                  {draft.data.kind === 'AMENDMENT' ? 'Чернетка доповнення' : 'Чернетка'}
                </Chip>
              ) : null}
            </div>
          ) : null}
          {archived ? (
            <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-ui text-secondary">
              Документ з архіву на {formatLongDate(order.date)}.
              <Button size="sm" variant="ghost" iconStart={<ArrowLeft aria-hidden="true" className="size-4 rtl:rotate-180" />} onClick={() => setViewed(null)}>
                До {formatWeekdayDateAfterDo(date)}
              </Button>
            </p>
          ) : null}
          {waiting}
          {isDraft && empty ? (
            <p className="text-body text-secondary">Нових підтверджених змін після опублікованого документа ще немає.</p>
          ) : wide ? (
            <div className="rounded-panel bg-sunken p-4 sm:p-6">
              <OrderSheet order={order} parentNumber={parent.data?.number ?? null} changes={changesById} fresh={fresh} />
            </div>
          ) : (
            <>
              <OrderRows order={order} />
              <details className="group">
                <summary className="inline-flex min-h-11 cursor-pointer list-none items-center rounded-control text-ui font-semibold text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus [&::-webkit-details-marker]:hidden">
                  <span className="group-open:hidden">Показати аркуш, як надрукують</span>
                  <span className="hidden group-open:inline">Сховати аркуш</span>
                </summary>
                {/* аркуш у натуральну ширину, щоб текст лишався читабельним; гортається вбік, як PDF */}
                <div className="mt-2 -mx-4 overflow-x-auto overscroll-x-contain bg-sunken px-4 py-4">
                  <div className="w-[40rem]">
                    <OrderSheet order={order} parentNumber={parent.data?.number ?? null} changes={changesById} fresh={fresh} />
                  </div>
                </div>
              </details>
            </>
          )}
        </div>

        <aside aria-label="Примітки й розсилка" className="flex min-w-0 flex-col gap-8 lg:sticky lg:top-[calc(var(--sticky-top)+1rem)]">
          {isDraft ? (
            <Section title="Примітки" description="Друкуються жирним під таблицею." className="gap-3" headingLevel={2}>
              <NotesEditor key={`${order.id}:${order.notes.join('\n')}`} order={order} />
            </Section>
          ) : (
            <Section title="Розсилка в Telegram" className="gap-3" headingLevel={2}>
              {order.imported ? (
                <p className="text-ui text-secondary">Паперове розпорядження, внесене як текст: файлів і розсилки для нього немає.</p>
              ) : (
                <DeliverySummary orderId={order.id} />
              )}
            </Section>
          )}
        </aside>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Розпорядження" meta={status} primaryAction={primary} more={more} />

      <PageErrorScope queries={[pageQuery]} title="Не вдалося скласти розпорядження">
        <WeekStrip date={date} onSelect={selectDay} />

        {main}

        <Section id="archive" title="Архів" className="mt-6">
        <OrdersArchive
          semester={semester}
          selectedId={viewedId}
          onOpen={(row) => {
            setViewed({ id: row.id, forDate: date });
            window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
          }}
          />
        </Section>
      </PageErrorScope>

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
