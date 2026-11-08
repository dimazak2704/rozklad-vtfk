import { CircleCheck } from 'lucide-react';
import { useState } from 'react';
import { useServerEvent } from '@/api/live';
import { useMyChanges, useReplyToChange } from '@/api/me';
import type { ChangeDto, Uuid } from '@/api/types';
import { ErrorState, Skeleton, notify } from '@/components/ui';
import { todayInKyiv } from '@/lib/date';
import { awaitingAnswer, changeTitle, isUnansweredNotice } from './change-text';
import { SubstitutionCard } from './SubstitutionCard';

/**
 * Заміни, що чекають моєї відповіді. Нові приходять наживо (SSE `change.updated`) — з підсвіткою картки й
 * сповіщенням; відповідь оптимістична (src/api/me.ts → useReplyToChange).
 */
export function SubstitutionRequests({ teacherId }: { teacherId: Uuid }) {
  const changes = useMyChanges();
  const reply = useReplyToChange();
  const [fresh, setFresh] = useState<ReadonlySet<Uuid>>(new Set());

  useServerEvent('change.updated', (change) => {
    if (change.new.teacher?.id !== teacherId) return;
    const request = change.status === 'PENDING_CONFIRMATION';
    const notice = isUnansweredNotice(change, todayInKyiv()) && change.history.length === 1;
    if (!request && !notice) return;
    setFresh((current) => new Set(current).add(change.id));
    notify.info(notice ? 'Вам поставили заміну' : 'Новий запит на заміну', { description: changeTitle(change) });
  });

  const waiting = awaitingAnswer(changes.data ?? [], todayInKyiv());

  const confirm = (change: ChangeDto) =>
    reply.mutate(
      { id: change.id, decision: 'CONFIRM' },
      {
        onSuccess: () =>
          change.approvalMode === 'NOTIFY'
            ? notify.success('Дякуємо!', { description: `${changeTitle(change)}. Пара вже у вашому розкладі.` })
            : notify.success('Заміну підтверджено', { description: `${changeTitle(change)}. Пара зʼявиться у вашому розкладі.` }),
      },
    );
  const decline = (change: ChangeDto, comment: string) =>
    reply.mutate(
      { id: change.id, decision: 'DECLINE', comment },
      {
        onSuccess: (updated) =>
          updated.status === 'APPLIED'
            ? notify.info('Диспетчера повідомлено', { description: 'Заміна вже в розпорядженні — диспетчер внесе доповнення.' })
            : notify.info('Ви відмовилися від заміни', { description: 'Диспетчер побачить відповідь і обере інший варіант.' }),
      },
    );

  let body;
  if (changes.isPending) {
    body = <Skeleton className="h-44 w-full rounded-panel" />;
  } else if (changes.isError) {
    body = (
      <ErrorState
        error={changes.error}
        size="sm"
        align="start"
        onRetry={() => void changes.refetch()}
        retrying={changes.isFetching}
        className="rounded-panel border border-default bg-surface"
      />
    );
  } else if (waiting.length === 0) {
    body = (
      <p className="flex items-center gap-2 rounded-panel border border-default bg-surface px-4 py-3 text-ui text-secondary">
        <CircleCheck aria-hidden="true" className="size-4 text-add" />
        Нових замін немає. Коли диспетчер поставить вас на заміну, вона зʼявиться тут і в Telegram.
      </p>
    );
  } else {
    body = (
      <ul className="flex flex-col gap-3">
        {waiting.map((change) => (
          <li key={change.id}>
            <SubstitutionCard
              change={change}
              fresh={fresh.has(change.id)}
              onConfirm={() => confirm(change)}
              onDecline={(comment) => decline(change, comment)}
            />
          </li>
        ))}
      </ul>
    );
  }

  return (
    <section aria-labelledby="requests-heading" className="flex flex-col gap-3">
      <h2 id="requests-heading" className="flex items-center gap-2 text-title font-semibold text-primary">
        Нові заміни
        {waiting.length > 0 ? (
          <span className="inline-flex h-6 min-w-6 items-center justify-center rounded-full bg-change px-1.5 text-caption font-bold text-on-change figures">
            {waiting.length}
          </span>
        ) : null}
      </h2>
      <div aria-live="polite">{body}</div>
    </section>
  );
}
