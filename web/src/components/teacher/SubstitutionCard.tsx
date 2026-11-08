import { Check, Clock, DoorOpen, X } from 'lucide-react';
import { useId, useState } from 'react';
import { useBells } from '@/api/meta';
import type { ChangeDto } from '@/api/types';
import { Badge, Button, Plate, Popover, PopoverClose, PopoverContent, PopoverTrigger, Textarea } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDayMonth, formatInstantTime, todayInKyiv } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { reasonHint } from '../public/schedule/schedule-utils';
import { changeDay, changeDiscipline, changeDisciplineName, changeReason, changeRoom } from './change-text';

const COMMENT_MAX = 500;

interface SubstitutionCardProps {
  change: ChangeDto;
  onConfirm: () => void;
  onDecline: (comment: string) => void;
  /** Щойно прийшов через живе оновлення — коротко підсвітити. */
  fresh?: boolean;
}

/**
 * Запит на заміну: коли, яка група й пара, що провести і замість кого, до коли відповісти.
 * «Підтверджую» — одним натисканням; «Не можу» — з необовʼязковим коментарем для диспетчера.
 */
export function SubstitutionCard({ change, onConfirm, onDecline, fresh = false }: SubstitutionCardProps) {
  const bells = useBells(change.date);
  const { now } = useNow();
  const [comment, setComment] = useState('');
  const commentId = useId();
  const slot = bells.data?.slots.find((s) => s.pair === change.pairFrom);
  const deadline = change.confirmDeadline ? new Date(change.confirmDeadline) : null;
  const overdue = deadline !== null && deadline.getTime() <= now.getTime();
  const discipline = change.new.discipline ?? change.original.discipline;
  const swapped = change.kind === 'REPLACE_DISCIPLINE' && change.original.discipline;
  const reason = changeReason(change);
  const hint = reasonHint(reason);
  const room = changeRoom(change);
  const disciplineName = changeDisciplineName(change);

  return (
    <article
      aria-label={`Запит на заміну: ${change.group.code}, ${changeDay(change).toLocaleLowerCase('uk')}, ${change.pairFrom} пара`}
      className={cn(
        'flex flex-col gap-3 rounded-panel border border-change bg-surface p-4',
        fresh && 'motion-safe:animate-[live-flash_2.4s_var(--ease-out)]',
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1.5">
        <p className="flex flex-wrap items-baseline gap-x-2 text-ui text-secondary">
          <span className="font-semibold text-primary">{changeDay(change, todayInKyiv(now))}</span>
          <span aria-hidden="true">·</span>
          <span className="font-condensed text-heading leading-none font-bold text-primary figures">{change.pairFrom} пара</span>
          {slot ? (
            <span className="figures">
              {slot.startsAt}–{slot.endsAt}
            </span>
          ) : null}
        </p>
        {deadline ? (
          <Badge tone={overdue ? 'cancel' : 'change'} size="sm" icon={<Clock aria-hidden="true" className="size-3" />}>
            {overdue
              ? 'термін відповіді минув'
              : `відповісти до ${formatInstantTime(deadline.toISOString())}, ${formatDayMonth(todayInKyiv(deadline))}`}
          </Badge>
        ) : null}
      </div>

      <div className="flex items-start gap-3">
        <Plate size="lg">{change.group.code}</Plate>
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="flex items-center gap-2">
            {discipline ? <span {...lineAttr(discipline)} aria-hidden="true" className="size-2.5 shrink-0 rounded-full bg-[var(--line-dot)]" /> : null}
            <span className="font-condensed text-title leading-tight font-bold text-primary">{changeDiscipline(change)}</span>
            {change.subgroup > 0 ? <Badge size="sm">{change.subgroup} підгрупа</Badge> : null}
          </p>
          {disciplineName ? <p className="text-caption text-secondary">{disciplineName}</p> : null}
          <p className="text-ui text-secondary">
            замість{' '}
            <span className="text-primary line-through decoration-[var(--text-decoration-color-muted)]">
              {[swapped ? change.original.discipline?.shortName : null, change.original.teacher?.displayName].filter(Boolean).join(', ') ||
                'викладача за розкладом'}
            </span>
            {reason ? (
              <span className="ms-2 font-semibold text-secondary" title={hint ?? undefined}>
                {reason}
                {hint ? <span className="sr-only"> ({hint})</span> : null}
              </span>
            ) : null}
          </p>
        </div>
      </div>

      {room ? (
        <p className="flex items-center gap-2 text-ui text-secondary">
          <DoorOpen aria-hidden="true" className="size-4 text-muted" />
          {room}
        </p>
      ) : null}

      <div className="flex flex-wrap items-center gap-2 border-t border-separator pt-3">
        <Button variant="primary" iconStart={<Check aria-hidden="true" className="size-4" />} onClick={onConfirm}>
          Підтверджую
        </Button>
        <Popover>
          <PopoverTrigger asChild>
            <Button variant="secondary" iconStart={<X aria-hidden="true" className="size-4" />}>
              Не можу
            </Button>
          </PopoverTrigger>
          <PopoverContent width="md" title="Відмовитися від заміни?">
            <form
              className="flex flex-col gap-3"
              onSubmit={(event) => {
                event.preventDefault();
                onDecline(comment);
              }}
            >
              <label htmlFor={commentId} className="text-caption text-secondary">
                Коментар для диспетчера (необовʼязково) — наприклад, чому не виходить.
              </label>
              <Textarea
                id={commentId}
                value={comment}
                maxLength={COMMENT_MAX}
                onChange={(event) => setComment(event.target.value)}
                rows={2}
              />
              <div className="flex justify-end gap-2">
                <PopoverClose asChild>
                  <Button variant="ghost" size="sm">
                    Скасувати
                  </Button>
                </PopoverClose>
                <Button type="submit" variant="danger" size="sm">
                  Відмовитися
                </Button>
              </div>
            </form>
          </PopoverContent>
        </Popover>
      </div>
    </article>
  );
}
