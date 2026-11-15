import type { ChangeDto, ChangeHistoryEntry, ChangeStatus, TeacherRef } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatInstantTime } from '@/lib/date';
import type { TeacherLabel } from './teacher-label';
import { useTeacherLabel } from './useTeacherLabel';

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

/** Примітки, які core пише сам, коли викладач нічого не додав: це не його слова, тож без лапок. */
const SYSTEM_COMMENTS = new Set(['Підтверджено викладачем', 'Викладач відмовився']);

function stepLabel(entry: ChangeHistoryEntry): string {
  if (entry.from === 'PENDING_CONFIRMATION' && entry.to === 'PENDING_CONFIRMATION') return 'Нагадування надіслано';
  return STEP_LABEL[entry.to];
}

/** Службові облікові записи, від яких бувають записи історії (бот повідомляє про відсутність тощо). */
const SERVICE_ACTORS: Record<string, string> = { 'telegram-bot': 'Telegram-бот', system: 'Система' };

/** Відповідь викладача, записана його логіном (кабінет): відмова чи відповідь на повідомлення без зміни стану. */
function isTeacherAnswer(entry: ChangeHistoryEntry): boolean {
  if (entry.to === 'DECLINED') return true;
  if (entry.from === 'PENDING_CONFIRMATION' && entry.to === 'CONFIRMED') return entry.comment !== 'Підтверджено диспетчером';
  return entry.from === entry.to && (entry.to === 'CONFIRMED' || entry.to === 'APPLIED');
}

/**
 * Хто діяв — словами, а не логіном: core пише відповідь з Telegram як «Кренцін М.Д. (Telegram)», а з кабінету й
 * дії диспетчера — логіном облікового запису. Викладача зміни показуємо його іменем (і його слова — в лапках),
 * службові записи — назвою, решту логінів — «Диспетчер».
 */
export function historyActor(
  entry: ChangeHistoryEntry,
  change: Pick<ChangeDto, 'new' | 'original'>,
  name: TeacherLabel,
): { label: string; teacher: boolean } {
  const teachers = [change.new.teacher, change.original.teacher].filter((t): t is TeacherRef => Boolean(t));
  for (const teacher of teachers) {
    if (entry.actor === teacher.shortName) return { label: name(teacher), teacher: true };
    if (entry.actor.startsWith(`${teacher.shortName} (`)) return { label: `${name(teacher)} ${entry.actor.slice(teacher.shortName.length + 1)}`, teacher: true };
  }
  const service = SERVICE_ACTORS[entry.actor];
  if (service) return { label: service, teacher: false };
  // логін без пробілів: відповідь викладача з кабінету — його ім'ям, решта — дії диспетчера
  if (!/\s/.test(entry.actor)) {
    if (change.new.teacher && isTeacherAnswer(entry)) return { label: name(change.new.teacher), teacher: true };
    return { label: 'Диспетчер', teacher: false };
  }
  return { label: entry.actor, teacher: false };
}

/**
 * Історія рішення як лінія маршруту: кожна зміна стану — зупинка з часом, хто діяв і коментар.
 * Остання зупинка — поточний стан.
 */
export function ChangeTimeline({ change, className }: { change: ChangeDto; className?: string }) {
  const name = useTeacherLabel();
  const steps = change.history;
  if (steps.length === 0) return null;
  return (
    <ol aria-label="Історія рішення" className={cn('flex flex-col', className)}>
      {steps.map((entry, index) => {
        const last = index === steps.length - 1;
        const actor = historyActor(entry, change, name);
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
                {actor.label}
                {entry.comment ? (
                  <span className="text-secondary">
                    {' · '}
                    {/* слова викладача — в лапках, службові примітки системи — як є */}
                    {actor.teacher && !SYSTEM_COMMENTS.has(entry.comment) ? `«${entry.comment}»` : entry.comment}
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
