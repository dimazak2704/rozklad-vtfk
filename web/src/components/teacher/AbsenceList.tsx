import { Trash2 } from 'lucide-react';
import { useDeleteAbsence, useMyAbsences } from '@/api/me';
import type { AbsenceDto, AbsenceSource, IsoDate } from '@/api/types';
import { Badge, EmptyState, ErrorState, IconButton, LoadingRegion, Skeleton, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { compareIsoDates, formatDateRange, parseIsoDate } from '@/lib/date';
import { ABSENCE_REASON_LABEL, countLabel } from '@/lib/labels';
import { confirm } from '@/stores/confirm';

const SOURCE_LABEL: Record<AbsenceSource, string> = { WEB: 'з сайту', BOT: 'через Telegram', ADMIN: 'диспетчер' };

function pairsText(pairs: readonly number[] | null | undefined): string {
  if (!pairs || pairs.length === 0) return 'увесь день';
  return `${pairs.join(', ')} ${pairs.length === 1 ? 'пара' : 'пари'}`;
}

function AbsenceRow({ absence, today, deletable }: { absence: AbsenceDto; today: IsoDate; deletable: boolean }) {
  const remove = useDeleteAbsence();
  const range = formatDateRange(absence.dateFrom, absence.dateTo);
  const ongoing = compareIsoDates(absence.dateFrom, today) <= 0 && compareIsoDates(absence.dateTo, today) >= 0;

  const onDelete = async () => {
    const confirmed = await confirm({
      title: 'Видалити відсутність?',
      description: 'Якщо на ці пари вже призначили заміну, повідомте диспетчера окремо.',
      objectName: `${range}, ${pairsText(absence.pairs)}`,
      confirmLabel: 'Видалити',
      tone: 'danger',
    });
    if (!confirmed) return;
    remove.mutate(absence.id, { onSuccess: () => notify.success('Відсутність видалено', { description: range }) });
  };

  return (
    <li className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-3 py-3 first:pt-0 last:pb-0">
      <div className="flex min-w-0 flex-col gap-1">
        <p className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <span className="font-condensed text-title leading-tight font-bold text-primary figures">{range}</span>
          {parseIsoDate(absence.dateFrom).year !== parseIsoDate(today).year ? (
            <span className="text-caption text-muted figures">{parseIsoDate(absence.dateFrom).year}</span>
          ) : null}
          {ongoing ? (
            <Badge tone="change" size="sm">
              триває
            </Badge>
          ) : null}
        </p>
        <p className="text-ui text-secondary">
          {pairsText(absence.pairs)} · {ABSENCE_REASON_LABEL[absence.reasonCategory]} · {SOURCE_LABEL[absence.source]}
        </p>
        {absence.comment ? <p className="text-caption text-muted">«{absence.comment}»</p> : null}
      </div>
      {deletable ? (
        <IconButton
          label={`Видалити відсутність ${range}`}
          variant="danger-ghost"
          icon={<Trash2 aria-hidden="true" className="size-4" />}
          loading={remove.isPending}
          onClick={() => void onDelete()}
        />
      ) : null}
    </li>
  );
}

/** Мої відсутності: попереду (майбутні можна видалити) і минулі. */
export function AbsenceList({ today }: { today: IsoDate }) {
  const absences = useMyAbsences();

  if (absences.isPending) {
    return (
      <LoadingRegion label="Завантажуємо відсутності…">
        <div className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-4 w-64" />
          <Skeleton className="h-6 w-40" />
        </div>
      </LoadingRegion>
    );
  }
  if (absences.isError) {
    return (
      <ErrorState
        error={absences.error}
        onRetry={() => void absences.refetch()}
        retrying={absences.isFetching}
        className="rounded-panel border border-default bg-surface"
      />
    );
  }

  const upcoming = absences.data
    .filter((a) => compareIsoDates(a.dateTo, today) >= 0)
    .sort((a, b) => compareIsoDates(a.dateFrom, b.dateFrom));
  const past = absences.data.filter((a) => compareIsoDates(a.dateTo, today) < 0).sort((a, b) => compareIsoDates(b.dateFrom, a.dateFrom));

  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="absences-upcoming" className="flex flex-col gap-3">
        <h2 id="absences-upcoming" className="text-title font-semibold text-primary">
          Попереду
        </h2>
        {upcoming.length === 0 ? (
          <EmptyState
            align="start"
            size="sm"
            className="rounded-panel border border-default bg-surface"
            title="Відсутностей попереду немає"
            description="Якщо захворієте чи поїдете у відрядження, повідомте у формі — диспетчер одразу побачить і підбере заміни."
          />
        ) : (
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface p-4">
            {upcoming.map((absence) => (
              <AbsenceRow key={absence.id} absence={absence} today={today} deletable={compareIsoDates(absence.dateFrom, today) > 0} />
            ))}
          </ul>
        )}
        {upcoming.some((a) => compareIsoDates(a.dateFrom, today) <= 0) ? (
          <p className="text-caption text-muted">Відсутність, що вже почалася, змінює лише диспетчер.</p>
        ) : null}
      </section>

      {past.length > 0 ? (
        <section aria-labelledby="absences-past" className="flex flex-col gap-3">
          <h2 id="absences-past" className="flex items-baseline gap-2 text-heading font-semibold text-primary">
            Минулі
            <span className="text-caption font-normal text-muted">{countLabel(past.length, ['відсутність', 'відсутності', 'відсутностей'])}</span>
          </h2>
          <ul className={cn('flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-sunken p-4')}>
            {past.map((absence) => (
              <AbsenceRow key={absence.id} absence={absence} today={today} deletable={false} />
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
