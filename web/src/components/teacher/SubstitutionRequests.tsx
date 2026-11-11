import { useState } from 'react';
import { useServerEvent } from '@/api/live';
import { useMyChanges, useReplyToChange } from '@/api/me';
import type { ChangeDto, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { todayInKyiv } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { awaitingAnswer, changeTitle, isUnansweredNotice } from './change-text';
import { SubstitutionCard } from './SubstitutionCard';

const REQUEST_FORMS = ['заміна чекає', 'заміни чекають', 'замін чекають'] as const;

/**
 * Заміни, що чекають моєї відповіді, — головна дія кабінету, тому стоять угорі. Немає запитів — немає й
 * блоку (спокійне речення про це — у «Моїх замінах»). Нові приходять наживо (SSE `change.updated`) —
 * з підсвіткою рядка й сповіщенням; відповідь оптимістична (src/api/me.ts → useReplyToChange).
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

  // помилку й завантаження показує сторінка (одна на всіх); тут — лише те, що треба вирішити
  return (
    <section aria-labelledby="requests-heading" aria-live="polite" className={waiting.length === 0 ? 'hidden' : 'flex flex-col gap-3'}>
      {waiting.length > 0 ? (
        <>
          <h2 id="requests-heading" className="text-title text-primary">
            {countLabel(waiting.length, REQUEST_FORMS)} вашої відповіді
          </h2>
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-change bg-surface">
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
        </>
      ) : null}
    </section>
  );
}
