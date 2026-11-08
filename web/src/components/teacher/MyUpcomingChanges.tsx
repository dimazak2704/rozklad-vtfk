import { useMyChanges } from '@/api/me';
import type { ChangeDto } from '@/api/types';
import { Badge, Plate, Skeleton } from '@/components/ui';
import { compareIsoDates, formatShortWeekdayDate, todayInKyiv } from '@/lib/date';
import { changeDiscipline } from './change-text';

const STATUS: Partial<Record<ChangeDto['status'], { label: string; tone: 'success' | 'accent' }>> = {
  CONFIRMED: { label: 'Підтверджено', tone: 'success' },
  APPLIED: { label: 'У розпорядженні', tone: 'accent' },
};

/** Мої майбутні заміни: підтверджені й ті, що вже потрапили в опубліковане розпорядження. */
export function MyUpcomingChanges() {
  const changes = useMyChanges();
  const today = todayInKyiv();
  const upcoming = (changes.data ?? [])
    .filter((c) => (c.status === 'CONFIRMED' || c.status === 'APPLIED') && compareIsoDates(c.date, today) >= 0)
    .sort((a, b) => compareIsoDates(a.date, b.date) || a.pairFrom - b.pairFrom);

  return (
    <section aria-labelledby="my-changes-heading" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
      <h2 id="my-changes-heading" className="text-heading font-semibold text-primary">
        Мої заміни
      </h2>
      {changes.isPending ? (
        <div className="flex flex-col gap-2" aria-hidden="true">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : changes.isError ? (
        <p className="text-ui text-secondary">Список замін не завантажився — спробуйте оновити сторінку.</p>
      ) : upcoming.length === 0 ? (
        <p className="text-ui text-secondary">Підтверджених замін попереду немає.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-[var(--border-color-separator)]">
          {upcoming.map((change) => {
            const status = STATUS[change.status];
            return (
              <li key={change.id} className="flex items-center gap-3 py-2.5 first:pt-0 last:pb-0">
                <span className="w-16 shrink-0 text-caption font-semibold text-secondary figures">
                  {formatShortWeekdayDate(change.date)}
                  <span className="block font-condensed text-heading leading-none font-bold text-primary">{change.pairFrom} пара</span>
                </span>
                <span className="flex min-w-0 flex-1 flex-col gap-1">
                  <span className="flex items-center gap-2">
                    <Plate size="sm">{change.group.code}</Plate>
                    <span className="truncate font-condensed text-ui font-bold text-primary">{changeDiscipline(change)}</span>
                  </span>
                  {status ? (
                    <Badge tone={status.tone} size="sm" className="self-start">
                      {status.label}
                    </Badge>
                  ) : null}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
