import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ChevronRight, Trash2 } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { api } from '@/api/client';
import { useMyAbsences } from '@/api/me';
import { qk } from '@/api/queryKeys';
import type { AbsenceDto, AbsenceSource, IsoDate, Uuid } from '@/api/types';
import { ErrorState, IconButton, LoadingRegion, Skeleton, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { compareIsoDates, formatDateRange, parseIsoDate } from '@/lib/date';
import { ABSENCE_REASON_LABEL, countLabel } from '@/lib/labels';

/** Скільки часу є, щоб передумати: стільки ж, скільки живе сповіщення з «Скасувати». */
const UNDO_MS = 8_000;

/** Хто повідомив — лише коли не я сам (з сайту й через бота — однаково «я»). */
const SOURCE_NOTE: Partial<Record<AbsenceSource, string>> = { ADMIN: 'вніс диспетчер' };

/** «увесь день», «3 пара», «3 і 4 пари», «1, 2 і 5 пари». */
function pairsText(pairs: readonly number[] | null | undefined): string {
  if (!pairs || pairs.length === 0) return 'увесь день';
  if (pairs.length === 1) return `${pairs[0]} пара`;
  return `${pairs.slice(0, -1).join(', ')} і ${pairs[pairs.length - 1]} пари`;
}

/**
 * Видалення з можливістю скасувати: рядок зникає одразу, а запит іде лише через 8 с, якщо людина не натиснула
 * «Скасувати». Так диспетчер не бачить «видалили — повернули», а випадковий клік нічого не ламає.
 * Якщо сторінку закрили раніше, запит не встигне піти — відсутність просто лишиться (безпечний бік).
 */
function useDeferredDelete() {
  const queryClient = useQueryClient();
  const key = qk.me.absences();
  const [hidden, setHidden] = useState<ReadonlySet<Uuid>>(new Set());
  const timers = useRef(new Map<Uuid, ReturnType<typeof setTimeout>>());

  const remove = useMutation({
    mutationFn: (absence: AbsenceDto) => api.delete(`/me/absences/${encodeURIComponent(absence.id)}`),
    onSuccess: (_result, absence) => queryClient.setQueryData<AbsenceDto[]>(key, (list) => list?.filter((a) => a.id !== absence.id)),
    onError: (error, absence) => {
      setHidden((current) => {
        const next = new Set(current);
        next.delete(absence.id);
        return next;
      });
      notify.error(error, `Не вдалося видалити відсутність ${formatDateRange(absence.dateFrom, absence.dateTo)}`);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
  });

  // піти зі сторінки — не те саме, що передумати: відкладені видалення виконуємо одразу
  const pending = useRef(new Map<Uuid, AbsenceDto>());
  const mutate = remove.mutate;
  useEffect(() => {
    const map = timers.current;
    const queue = pending.current;
    return () => {
      for (const [id, timer] of map) {
        clearTimeout(timer);
        const absence = queue.get(id);
        if (absence) mutate(absence);
      }
      map.clear();
      queue.clear();
    };
  }, [mutate]);

  const start = (absence: AbsenceDto) => {
    setHidden((current) => new Set(current).add(absence.id));
    pending.current.set(absence.id, absence);
    const timer = setTimeout(() => {
      timers.current.delete(absence.id);
      pending.current.delete(absence.id);
      mutate(absence);
    }, UNDO_MS);
    timers.current.set(absence.id, timer);
    notify.undo(`Відсутність ${formatDateRange(absence.dateFrom, absence.dateTo)} видалено`, {
      duration: UNDO_MS,
      onUndo: () => {
        clearTimeout(timers.current.get(absence.id));
        timers.current.delete(absence.id);
        pending.current.delete(absence.id);
        setHidden((current) => {
          const next = new Set(current);
          next.delete(absence.id);
          return next;
        });
      },
    });
  };

  return { hidden, start };
}

function AbsenceRow({ absence, today, onDelete }: { absence: AbsenceDto; today: IsoDate; onDelete?: () => void }) {
  const range = formatDateRange(absence.dateFrom, absence.dateTo);
  const ongoing = compareIsoDates(absence.dateFrom, today) <= 0 && compareIsoDates(absence.dateTo, today) >= 0;
  const year = parseIsoDate(absence.dateFrom).year;
  const details = [pairsText(absence.pairs), ABSENCE_REASON_LABEL[absence.reasonCategory], SOURCE_NOTE[absence.source]].filter(Boolean).join(' · ');

  return (
    <li className="group grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 py-3.5">
      <div className="flex min-w-0 flex-col gap-0.5">
        <p className="text-cell text-primary">
          <span className="figures">{range}</span>
          {year !== parseIsoDate(today).year ? <span className="font-[500] text-muted figures"> {year}</span> : null}
        </p>
        <p className="text-caption text-secondary">{details}</p>
        {ongoing ? <p className="text-caption text-change">Триває — змінити її може лише диспетчер.</p> : null}
        {absence.comment ? <p className="text-caption text-muted">«{absence.comment}»</p> : null}
      </div>
      {onDelete ? (
        <IconButton
          label={`Видалити відсутність ${range}`}
          variant="danger-ghost"
          icon={<Trash2 aria-hidden="true" className="size-4" />}
          onClick={onDelete}
          // у спокої рядок без кнопок; при наведенні чи фокусі — видно, на дотику — завжди
          className="opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100"
        />
      ) : null}
    </li>
  );
}

/** Мої відсутності: попереду (майбутні можна видалити з «Скасувати») і минулі — згорнуті. */
export function AbsenceList({ today }: { today: IsoDate }) {
  const absences = useMyAbsences();
  const { hidden, start } = useDeferredDelete();

  if (absences.isPending) {
    return (
      <LoadingRegion label="Завантажуємо відсутності…">
        <div className="flex flex-col gap-3">
          <Skeleton className="h-7 w-32" />
          <Skeleton className="h-5 w-48" />
          <Skeleton className="h-4 w-64" />
        </div>
      </LoadingRegion>
    );
  }
  if (absences.isError) {
    return <ErrorState error={absences.error} onRetry={() => void absences.refetch()} retrying={absences.isFetching} />;
  }

  const visible = absences.data.filter((a) => !hidden.has(a.id));
  const upcoming = visible.filter((a) => compareIsoDates(a.dateTo, today) >= 0).sort((a, b) => compareIsoDates(a.dateFrom, b.dateFrom));
  const past = visible.filter((a) => compareIsoDates(a.dateTo, today) < 0).sort((a, b) => compareIsoDates(b.dateFrom, a.dateFrom));

  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="absences-upcoming" className="flex flex-col gap-2">
        <h2 id="absences-upcoming" className="text-heading font-[700] text-primary">
          Попереду
        </h2>
        {upcoming.length === 0 ? (
          <p className="py-2 text-ui text-secondary">Відсутностей попереду немає. Якщо захворієте чи поїдете у відрядження, повідомте у формі — диспетчер одразу підбере заміни.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] border-y border-separator">
            {upcoming.map((absence) => (
              <AbsenceRow
                key={absence.id}
                absence={absence}
                today={today}
                onDelete={compareIsoDates(absence.dateFrom, today) > 0 ? () => start(absence) : undefined}
              />
            ))}
          </ul>
        )}
      </section>

      {past.length > 0 ? (
        <details className="group/past flex flex-col">
          <summary
            className={cn(
              'flex min-h-11 w-fit cursor-pointer list-none items-center gap-1.5 rounded-control text-ui font-[650] text-secondary hover:text-primary',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus [&::-webkit-details-marker]:hidden',
            )}
          >
            <ChevronRight aria-hidden="true" className="size-4 transition-transform duration-150 group-open/past:rotate-90" />
            Минулі: {countLabel(past.length, ['відсутність', 'відсутності', 'відсутностей'])}
          </summary>
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] border-y border-separator">
            {past.map((absence) => (
              <AbsenceRow key={absence.id} absence={absence} today={today} />
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}
