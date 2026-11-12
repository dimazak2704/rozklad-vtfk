import { useMyChanges } from '@/api/me';
import type { BadgeTone } from '@/components/ui';
import { ErrorState, Skeleton, TruncatedText } from '@/components/ui';
import { cn } from '@/lib/cn';
import { compareIsoDates, todayInKyiv } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { awaitingAnswer, changeDisciplineTitle, changeStatusBadge, changeWhen } from './change-text';

const TONE_TEXT: Partial<Record<BadgeTone, string>> = {
  success: 'text-add',
  accent: 'text-secondary',
  cancel: 'text-cancel',
  change: 'text-change',
};

/**
 * Мої майбутні заміни: підтверджені й ті, що вже в опублікованому розпорядженні. Стан — словом у кольорі,
 * без табличок. Якщо замін немає й нічого не чекає відповіді — одне спокійне речення про те, що буде далі.
 */
export function MyUpcomingChanges() {
  const changes = useMyChanges();
  const today = todayInKyiv();
  const all = changes.data ?? [];
  const upcoming = all
    .filter((c) => (c.status === 'CONFIRMED' || c.status === 'APPLIED') && compareIsoDates(c.date, today) >= 0)
    .filter((c) => !awaitingAnswer([c], today).length)
    .sort((a, b) => compareIsoDates(a.date, b.date) || a.pairFrom - b.pairFrom);
  const waiting = awaitingAnswer(all, today).length;

  let body;
  if (changes.isPending) {
    body = (
      <div className="flex flex-col gap-2" aria-hidden="true">
        <Skeleton className="h-5 w-3/4" />
        <Skeleton className="h-5 w-1/2" />
      </div>
    );
  } else if (changes.isError) {
    body = <ErrorState error={changes.error} size="sm" onRetry={() => void changes.refetch()} retrying={changes.isFetching} className="py-0" />;
  } else if (upcoming.length === 0) {
    // нічого не чекає й нічого не заплановано — кажемо, що буде, коли щось зʼявиться
    if (waiting > 0) return null;
    body = (
      <p className="text-ui text-secondary">
        Нових запитів на заміну немає. Коли диспетчер попросить вас замінити колегу, запит зʼявиться вгорі цієї сторінки й у Telegram.
      </p>
    );
  } else {
    body = (
      <ul className="flex flex-col divide-y divide-[var(--border-color-separator)]">
        {upcoming.map((change) => {
          const status = changeStatusBadge(change);
          const discipline = change.new.discipline ?? change.original.discipline;
          return (
            <li key={change.id} className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-x-4 py-3 first:pt-0 last:pb-0">
              <p className="flex flex-col">
                <span className="text-caption text-secondary">{changeWhen(change, today)}</span>
                <span className="text-ui font-[650] text-primary figures">{change.pairFrom} пара</span>
              </p>
              <div className="flex min-w-0 flex-col gap-0.5">
                <p className="flex min-w-0 items-baseline gap-2 text-ui font-[650] text-primary">
                  {discipline ? (
                    <span {...lineAttr(discipline)} aria-hidden="true" className="size-2 shrink-0 self-center rounded-full bg-[var(--line-dot)]" />
                  ) : null}
                  {/* вузький рядок обрізає назву — наведення мишею показує її повністю */}
                  <TruncatedText className="min-w-0 truncate">
                    {changeDisciplineTitle(change)}
                    <span className="font-[500] text-secondary"> · {change.group.code}</span>
                  </TruncatedText>
                </p>
                {status ? <p className={cn('text-caption', TONE_TEXT[status.tone] ?? 'text-secondary')}>{status.label}</p> : null}
              </div>
            </li>
          );
        })}
      </ul>
    );
  }

  return (
    <section aria-labelledby="my-changes-heading" className="flex flex-col gap-3">
      <h2 id="my-changes-heading" className="text-title text-primary">
        Мої заміни
      </h2>
      {body}
    </section>
  );
}
