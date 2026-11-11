import { useEffect, useRef, useState } from 'react';
import { useServerEvent } from '@/api/live';
import { useMyChanges, useReplyToChange, type ReplyVariables } from '@/api/me';
import type { ChangeDto, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { todayInKyiv } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { awaitingAnswer, changeTitle, isUnansweredNotice, mostUrgent, REPLY_UNDO_MS } from './change-text';
import { SubstitutionCard } from './SubstitutionCard';

const REQUEST_FORMS = ['заміна чекає', 'заміни чекають', 'замін чекають'] as const;

/** Що сказати після відповіді — до того, як вона піде на сервер (сповіщення з «Скасувати»). */
function replyToast(change: ChangeDto, decision: ReplyVariables['decision']): { message: string; description: string } {
  const title = changeTitle(change);
  if (decision === 'CONFIRM') {
    return change.approvalMode === 'NOTIFY'
      ? { message: 'Дякуємо, відповідь надіслано', description: `${title}. Пара вже у вашому розкладі.` }
      : { message: 'Заміну підтверджено', description: `${title}. Пара зʼявиться у вашому розкладі.` };
  }
  return change.approvalMode === 'NOTIFY' && change.status === 'APPLIED'
    ? { message: 'Диспетчера повідомлено', description: 'Заміна вже в розпорядженні — диспетчер внесе доповнення.' }
    : { message: 'Ви відмовилися від заміни', description: 'Диспетчер побачить відповідь і обере інший варіант.' };
}

/**
 * Відповідь з можливістю передумати: рядок зникає одразу, а запит іде лише через 8 с, якщо людина не натиснула
 * «Скасувати» (так само, як видалення відсутності). Піти зі сторінки — не передумати: відкладені відповіді
 * йдуть одразу. Якщо сервер відмовив, рядок повертається з поясненням (src/api/me.ts → useReplyToChange).
 */
function useDeferredReply() {
  const reply = useReplyToChange();
  const [held, setHeld] = useState<ReadonlySet<Uuid>>(new Set());
  const pending = useRef(new Map<Uuid, { timer: ReturnType<typeof setTimeout>; variables: ReplyVariables }>());
  const mutate = reply.mutate;

  const release = (id: Uuid) =>
    setHeld((current) => {
      if (!current.has(id)) return current;
      const next = new Set(current);
      next.delete(id);
      return next;
    });

  // рядок лишається схованим, доки кеш не отримає нову відповідь (успіх) чи не повернеться назад (помилка)
  const send = (variables: ReplyVariables) => mutate(variables, { onSettled: () => release(variables.id) });

  useEffect(() => {
    const queue = pending.current;
    return () => {
      for (const { timer, variables } of queue.values()) {
        clearTimeout(timer);
        mutate(variables);
      }
      queue.clear();
    };
  }, [mutate]);

  const answer = (change: ChangeDto, decision: ReplyVariables['decision'], comment?: string) => {
    const variables: ReplyVariables = { id: change.id, decision, comment };
    setHeld((current) => new Set(current).add(change.id));
    const timer = setTimeout(() => {
      pending.current.delete(change.id);
      send(variables);
    }, REPLY_UNDO_MS);
    pending.current.set(change.id, { timer, variables });
    const { message, description } = replyToast(change, decision);
    notify.undo(message, {
      description,
      duration: REPLY_UNDO_MS,
      onUndo: () => {
        const entry = pending.current.get(change.id);
        if (!entry) return;
        clearTimeout(entry.timer);
        pending.current.delete(change.id);
        release(change.id);
      },
    });
  };

  return { held, answer };
}

/**
 * Заміни, що чекають моєї відповіді, — головна дія кабінету, тому стоять угорі. Немає запитів — немає й
 * блоку (спокійне речення про це — у «Моїх замінах»). Нові приходять наживо (SSE `change.updated`) —
 * з підсвіткою рядка й сповіщенням. Відповідь можна скасувати протягом 8 с.
 */
export function SubstitutionRequests({ teacherId }: { teacherId: Uuid }) {
  const changes = useMyChanges();
  const { held, answer } = useDeferredReply();
  const [fresh, setFresh] = useState<ReadonlySet<Uuid>>(new Set());

  useServerEvent('change.updated', (change) => {
    if (change.new.teacher?.id !== teacherId) return;
    const request = change.status === 'PENDING_CONFIRMATION';
    const notice = isUnansweredNotice(change, todayInKyiv()) && change.history.length === 1;
    if (!request && !notice) return;
    setFresh((current) => new Set(current).add(change.id));
    notify.info(notice ? 'Вам поставили заміну' : 'Новий запит на заміну', { description: changeTitle(change) });
  });

  const waiting = awaitingAnswer(changes.data ?? [], todayInKyiv()).filter((change) => !held.has(change.id));
  const urgent = mostUrgent(waiting, new Date());

  // помилку й завантаження показує сторінка (одна на всіх); тут — лише те, що треба вирішити
  return (
    <section aria-labelledby="requests-heading" aria-live="polite" className={waiting.length === 0 ? 'hidden' : 'flex flex-col gap-3'}>
      {waiting.length > 0 ? (
        <>
          <h2 id="requests-heading" className="text-title text-primary">
            {countLabel(waiting.length, REQUEST_FORMS)} вашої відповіді
          </h2>
          {/* пласка панель на лініях; про терміновість каже бурштиновий рядок стану, а не рамка */}
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface">
            {waiting.map((change) => (
              <li key={change.id}>
                <SubstitutionCard
                  change={change}
                  primary={change.id === urgent}
                  fresh={fresh.has(change.id)}
                  onConfirm={() => answer(change, 'CONFIRM')}
                  onDecline={(comment) => answer(change, 'DECLINE', comment)}
                />
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}
