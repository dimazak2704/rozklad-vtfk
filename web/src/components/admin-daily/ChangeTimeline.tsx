import type { ChangeDto, ChangeHistoryEntry, ChangeStatus } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatInstantTime } from '@/lib/date';

const STEP_LABEL: Record<ChangeStatus, string> = {
  DRAFT: 'Створено чернетку',
  PENDING_CONFIRMATION: 'Запит на підтвердження',
  CONFIRMED: 'Підтверджено',
  DECLINED: 'Відмова',
  CANCELLED: 'Рішення скасовано',
  APPLIED: 'Внесено в розпорядження',
};

const DOT: Record<ChangeStatus, string> = {
  DRAFT: 'border-strong bg-surface',
  PENDING_CONFIRMATION: 'border-change bg-[var(--fill-change)]',
  CONFIRMED: 'border-add bg-[var(--fill-add)]',
  DECLINED: 'border-cancel bg-[var(--fill-cancel)]',
  CANCELLED: 'border-strong bg-surface',
  APPLIED: 'border-accent bg-[var(--fill-accent)]',
};

function stepLabel(entry: ChangeHistoryEntry): string {
  if (entry.from === 'PENDING_CONFIRMATION' && entry.to === 'PENDING_CONFIRMATION') return 'Нагадування надіслано';
  return STEP_LABEL[entry.to];
}

/**
 * Історія рішення як лінія маршруту: кожна зміна стану — зупинка з часом, хто діяв і коментар.
 * Остання зупинка — поточний стан.
 */
export function ChangeTimeline({ change, className }: { change: ChangeDto; className?: string }) {
  const steps = change.history;
  if (steps.length === 0) return null;
  return (
    <ol aria-label="Історія рішення" className={cn('flex flex-col', className)}>
      {steps.map((entry, index) => {
        const last = index === steps.length - 1;
        return (
          <li key={`${entry.at}-${index}`} className="grid grid-cols-[2.75rem_1rem_minmax(0,1fr)] gap-x-2">
            <time dateTime={entry.at} className="pt-px text-end text-meta font-semibold text-muted figures">
              {formatInstantTime(entry.at)}
            </time>
            <span aria-hidden="true" className="relative flex justify-center">
              <span className={cn('z-10 mt-1 size-2.5 rounded-full border-2', DOT[entry.to])} />
              {!last ? <span className="absolute top-3.5 bottom-0 w-px bg-[var(--border-color-default)]" /> : null}
            </span>
            <div className={cn('flex min-w-0 flex-col pb-3', last && 'pb-0')}>
              <p className={cn('text-ui', last ? 'font-semibold text-primary' : 'text-secondary')}>{stepLabel(entry)}</p>
              <p className="text-caption text-muted">
                {entry.actor}
                {entry.comment ? (
                  <span className="text-secondary">
                    {' · '}
                    {/* слова викладача — в лапках, службові примітки системи — як є */}
                    {entry.actor === change.new.teacher?.shortName ? `«${entry.comment}»` : entry.comment}
                  </span>
                ) : null}
              </p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
