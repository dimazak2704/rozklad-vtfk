import { CircleCheck } from 'lucide-react';
import { useState } from 'react';
import { useServerEvent } from '@/api/live';
import { useMyChanges, useReplyToChange } from '@/api/me';
import type { ChangeDto, Uuid } from '@/api/types';
import { ErrorState, Skeleton, notify } from '@/components/ui';
import { compareIsoDates } from '@/lib/date';
import { changeTitle } from './change-text';
import { SubstitutionCard } from './SubstitutionCard';

function byDateAndPair(a: ChangeDto, b: ChangeDto): number {
  return compareIsoDates(a.date, b.date) || a.pairFrom - b.pairFrom;
}

/**
 * Запити на заміну, що чекають моєї відповіді. Нові приходять наживо (SSE `change.updated`) — з
 * підсвіткою картки й сповіщенням; відповідь оптимістична (src/api/me.ts → useReplyToChange).
 */
export function SubstitutionRequests({ teacherId }: { teacherId: Uuid }) {
  const changes = useMyChanges();
  const reply = useReplyToChange();
  const [fresh, setFresh] = useState<ReadonlySet<Uuid>>(new Set());

  useServerEvent('change.updated', (change) => {
    if (change.new.teacher?.id !== teacherId || change.status !== 'PENDING_CONFIRMATION') return;
    setFresh((current) => new Set(current).add(change.id));
    notify.info('Новий запит на заміну', { description: changeTitle(change) });
  });

  const pending = (changes.data ?? []).filter((c) => c.status === 'PENDING_CONFIRMATION').sort(byDateAndPair);

  const confirm = (change: ChangeDto) =>
    reply.mutate(
      { id: change.id, decision: 'CONFIRM' },
      { onSuccess: () => notify.success('Заміну підтверджено', { description: `${changeTitle(change)}. Пара зʼявиться у вашому розкладі.` }) },
    );
  const decline = (change: ChangeDto, comment: string) =>
    reply.mutate(
      { id: change.id, decision: 'DECLINE', comment },
      { onSuccess: () => notify.info('Ви відмовилися від заміни', { description: 'Диспетчер побачить відповідь і підбере іншого викладача.' }) },
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
  } else if (pending.length === 0) {
    body = (
      <p className="flex items-center gap-2 rounded-panel border border-default bg-surface px-4 py-3 text-ui text-secondary">
        <CircleCheck aria-hidden="true" className="size-4 text-add" />
        Нових запитів на заміну немає. Коли диспетчер попросить вас замінити колегу, запит зʼявиться тут і в Telegram.
      </p>
    );
  } else {
    body = (
      <ul className="flex flex-col gap-3">
        {pending.map((change) => (
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
        Запити на заміну
        {pending.length > 0 ? (
          <span className="inline-flex h-6 min-w-6 items-center justify-center rounded-full bg-change px-1.5 text-caption font-bold text-on-change figures">
            {pending.length}
          </span>
        ) : null}
      </h2>
      <div aria-live="polite">{body}</div>
    </section>
  );
}
