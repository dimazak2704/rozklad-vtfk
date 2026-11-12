import { useId, useState } from 'react';
import { useBells } from '@/api/meta';
import type { ChangeDto } from '@/api/types';
import { Button, Popover, PopoverClose, PopoverContent, PopoverTrigger, Textarea } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatInstantTime, todayInKyiv } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { subgroupLabel } from '@/lib/labels';
import { changeDay, changeDisciplineTitle, changeReplaced, changeRoom, changeWhen, deadlineDay } from './change-text';

const COMMENT_MAX = 500;

interface SubstitutionCardProps {
  change: ChangeDto;
  onConfirm: () => void;
  onDecline: (comment: string) => void;
  /** Прохання з найближчим строком: лише воно має заповнену кнопку, а строк стоїть першим рядком. */
  primary?: boolean;
  /** Щойно прийшов через живе оновлення — коротко підсвітити. */
  fresh?: boolean;
}

/**
 * Заміна, що чекає відповіді, — один рядок списку: коли, що провести, у якій групі й де, замість кого.
 * Однакові дії в кожному рядку — «Підтверджую» / «Не можу»: для прохання (CONFIRM) це згода, для
 * повідомлення про призначену заміну (NOTIFY) — що викладач її бачив і проведе. «Не можу» — з необовʼязковим коментарем для диспетчера.
 * Будь-яку відповідь можна скасувати в сповіщенні протягом 8 с, тому підтверджувальних діалогів немає.
 */
export function SubstitutionCard({ change, onConfirm, onDecline, primary = false, fresh = false }: SubstitutionCardProps) {
  const bells = useBells(change.date);
  const { now } = useNow();
  const [comment, setComment] = useState('');
  const commentId = useId();
  const today = todayInKyiv(now);
  const slot = bells.data?.slots.find((s) => s.pair === change.pairFrom);
  const deadline = change.confirmDeadline ? new Date(change.confirmDeadline) : null;
  const overdue = deadline !== null && deadline.getTime() <= now.getTime();
  const discipline = change.new.discipline ?? change.original.discipline;
  const room = changeRoom(change);
  const replaced = changeReplaced(change);
  const notice = change.approvalMode === 'NOTIFY';
  const subgroup = change.subgroup > 0 ? subgroupLabel(change.subgroup) : null;

  // одне речення про стан замість трьох табличок: що це, до коли відповісти; строк — одним шматком, без переносу
  const due = deadline ? (
    <span className="whitespace-nowrap">
      до {formatInstantTime(deadline.toISOString())}
      {deadlineDay(deadline, today)}
    </span>
  ) : null;
  const state = notice ? (
    change.status === 'APPLIED' ? (
      'Вам поставили заміну, вона вже в розпорядженні'
    ) : (
      'Вам поставили заміну'
    )
  ) : overdue ? (
    'Просять замінити · термін відповіді минув'
  ) : due ? (
    primary ? (
      // найтерміновіший рядок: строк — перший рядок звичайним розміром, він і пояснює заповнену кнопку
      <>Відповісти {due}</>
    ) : (
      <>Просять замінити · відповісти {due}</>
    )
  ) : (
    'Просять замінити'
  );

  return (
    <article
      aria-label={`${notice ? 'Нова заміна' : 'Запит на заміну'}: ${change.group.code}, ${changeDay(change).toLocaleLowerCase('uk')}, ${change.pairFrom} пара`}
      className={cn(
        'grid gap-x-6 gap-y-3 px-4 py-4 sm:grid-cols-[7rem_minmax(0,1fr)] sm:px-5 lg:grid-cols-[7rem_minmax(0,1fr)_auto] lg:items-center',
        // прийшов наживо: закреслення по старому значенню, слово стану заходить на 4 px, рядок ледь тоновано
        // й тоновання тихо сходить — один раз, лише transform, opacity і clip-path
        fresh && 'anim-arrive relative isolate',
      )}
    >
      <p className="flex flex-wrap items-baseline gap-x-2 sm:flex-col sm:gap-0.5">
        <span className="text-caption font-[650] text-secondary">{changeWhen(change, today)}</span>
        <span className="text-heading text-primary figures">{change.pairFrom} пара</span>
        {slot ? <span className="text-caption text-muted figures">{slot.startsAt}</span> : null}
      </p>

      <div className="flex min-w-0 flex-col gap-1">
        <p
          className={cn(
            primary && due && !overdue ? 'text-body font-[650]' : 'text-caption font-[600]',
            overdue ? 'text-cancel' : 'text-change',
            fresh && 'anim-word-in',
          )}
        >
          {state}
        </p>
        {/* що й у кого провести — головний рядок: дисципліна жирно, код групи — Geologica в кольорі тексту */}
        <p className="flex min-w-0 items-baseline gap-2 text-cell font-[650] text-primary">
          {discipline ? (
            <span {...lineAttr(discipline)} aria-hidden="true" className="relative -top-px size-2 shrink-0 self-center rounded-full bg-[var(--line-dot)]" />
          ) : null}
          <span className="min-w-0">
            {changeDisciplineTitle(change)}
            {/* група тримається за останнє слово назви: рядок не починається з «·» */}
            <span className="whitespace-nowrap">
              <span className="font-[500] text-muted">{' · '}</span>
              <span className="font-display font-semibold tracking-[0.01em]">{change.group.code}</span>
              {subgroup ? <span className="font-[500] text-secondary">, {subgroup}</span> : null}
            </span>
          </span>
        </p>
        <p className="text-caption text-secondary">
          {replaced ? (
            <>
              замість <s className={cn('decoration-[var(--text-decoration-color-change)]', fresh && 'anim-strike')}>{replaced}</s>
            </>
          ) : null}
          {replaced && room ? ' · ' : null}
          {room ? <span className="figures">{room}</span> : null}
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-2 sm:col-start-2 lg:col-start-auto">
        <Button variant={primary ? 'primary' : 'secondary'} onClick={onConfirm}>
          Підтверджую
        </Button>
        <Popover>
          <PopoverTrigger asChild>
            <Button variant="ghost">Не можу</Button>
          </PopoverTrigger>
          <PopoverContent width="md" title={notice ? 'Не можете провести цю пару?' : 'Відмовитися від заміни?'}>
            <form
              className="flex flex-col gap-3"
              onSubmit={(event) => {
                event.preventDefault();
                onDecline(comment);
              }}
            >
              <label htmlFor={commentId} className="text-caption font-[650] text-primary">
                Коментар для диспетчера <span className="font-[500] text-muted">(необовʼязково)</span>
              </label>
              <Textarea
                id={commentId}
                value={comment}
                maxLength={COMMENT_MAX}
                placeholder="Наприклад: маю пару в іншому корпусі"
                onChange={(event) => setComment(event.target.value)}
                rows={2}
              />
              <div className="flex justify-end gap-2">
                <PopoverClose asChild>
                  <Button variant="ghost" size="sm">
                    Скасувати
                  </Button>
                </PopoverClose>
                {/* відмова — не втрата даних: звичайна кнопка, а передумати можна в сповіщенні */}
                <Button type="submit" variant="secondary" size="sm">
                  {notice ? 'Повідомити диспетчера' : 'Відмовитися'}
                </Button>
              </div>
            </form>
          </PopoverContent>
        </Popover>
      </div>
    </article>
  );
}
