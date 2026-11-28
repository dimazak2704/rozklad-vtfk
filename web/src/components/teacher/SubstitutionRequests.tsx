import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useServerEvent } from '@/api/live';
import { useBells } from '@/api/meta';
import { useMyChanges, useReplyToChange, type ReplyVariables } from '@/api/me';
import type { ChangeDto, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { useElementRegistry } from '@/hooks/useElementRegistry';
import { useNow } from '@/hooks/useNow';
import { now as clockNow } from '@/lib/clock';
import { formatDayMonth, relativeDayLabel, todayInKyiv } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { awaitingAnswer, changePassed, isUnansweredNotice, mostUrgent, REPLY_UNDO_MS } from './change-text';
import { SubstitutionCard } from './SubstitutionCard';

const REQUEST_FORMS = ['заміна чекає', 'заміни чекають', 'замін чекають'] as const;

/** Коротко, про яку пару: «4КІ2, завтра 1 пара» або «4КІ2, 16 листопада, 1 пара». */
function shortTitle(change: ChangeDto): string {
  const relative = relativeDayLabel(change.date, todayInKyiv());
  return relative
    ? `${change.group.code}, ${relative.toLocaleLowerCase('uk')} ${change.pairFrom}\u00a0пара`
    : `${change.group.code}, ${formatDayMonth(change.date)}, ${change.pairFrom}\u00a0пара`;
}

/**
 * Що сказати після відповіді — до того, як вона піде на сервер: один рядок і «Скасувати» (DESIGN §11.6).
 * Решту каже сам список: рядок зник, пара зʼявиться в розкладі.
 */
function replyToast(change: ChangeDto, decision: ReplyVariables['decision']): string {
  const title = shortTitle(change);
  if (decision === 'CONFIRM') return `Підтверджено: ${title}`;
  return change.approvalMode === 'NOTIFY' && change.status === 'APPLIED'
    ? `Диспетчера повідомлено: ${title}`
    : `Ви відмовилися: ${title}`;
}

/** Відповідь, що чекає кінця вікна «Скасувати». */
interface PendingReply {
  timer: ReturnType<typeof setTimeout>;
  variables: ReplyVariables;
  toast: string | number;
}

/**
 * Відповідь з можливістю передумати: рядок зникає одразу, а запит іде лише через 8 с, якщо людина не натиснула
 * «Скасувати» (так само, як видалення відсутності). Піти зі сторінки — не передумати: відкладені відповіді
 * йдуть одразу — і коли кабінет закривають у застосунку (unmount), і коли ховають чи закривають саму вкладку
 * або PWA (pagehide, visibilitychange → hidden): тоді таймер уже може не спрацювати. Запит із keepalive
 * (src/api/me.ts) доходить і після закриття. Кожна відповідь іде рівно раз: хто перший забрав її з черги
 * (таймер, приховування, unmount), той і шле. Якщо сервер відмовив, рядок повертається з поясненням.
 */
function useDeferredReply() {
  const reply = useReplyToChange();
  const [held, setHeld] = useState<ReadonlySet<Uuid>>(new Set());
  const pending = useRef(new Map<Uuid, PendingReply>());
  const mutate = reply.mutate;

  const release = useCallback(
    (id: Uuid) =>
      setHeld((current) => {
        if (!current.has(id)) return current;
        const next = new Set(current);
        next.delete(id);
        return next;
      }),
    [],
  );

  /** Забирає відповідь із черги: після цього її вже не надішле ніхто інший і не скасує «Скасувати». */
  const take = useCallback((id: Uuid): PendingReply | undefined => {
    const entry = pending.current.get(id);
    if (!entry) return undefined;
    pending.current.delete(id);
    clearTimeout(entry.timer);
    return entry;
  }, []);

  // рядок лишається схованим, доки кеш не отримає нову відповідь (успіх) чи не повернеться назад (помилка).
  // Сповіщення з «Скасувати» закриваємо тут, а не чекаємо його власного відліку: Sonner ставить його на паузу,
  // поки над ним курсор, і кнопка пережила б уже надіслану відповідь.
  const send = useCallback(
    (id: Uuid) => {
      const entry = take(id);
      if (!entry) return;
      notify.dismiss(entry.toast);
      mutate(entry.variables, { onSettled: () => release(id) });
    },
    [mutate, release, take],
  );

  useEffect(() => {
    const queue = pending.current;
    // сторінку ховають або закривають: надсилаємо зараз, «Скасувати» в сповіщенні вже нічого не скасує
    const flush = () => {
      for (const id of [...queue.keys()]) send(id);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') flush();
    };
    window.addEventListener('pagehide', flush);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', flush);
      document.removeEventListener('visibilitychange', onVisibility);
      for (const id of [...queue.keys()]) {
        const entry = take(id);
        if (!entry) continue;
        notify.dismiss(entry.toast);
        mutate(entry.variables);
      }
    };
  }, [mutate, send, take]);

  const answer = (change: ChangeDto, decision: ReplyVariables['decision'], comment?: string) => {
    const variables: ReplyVariables = { id: change.id, decision, comment };
    setHeld((current) => new Set(current).add(change.id));
    const timer = setTimeout(() => send(change.id), REPLY_UNDO_MS);
    const toast = notify.undo(replyToast(change, decision), {
      duration: REPLY_UNDO_MS,
      onUndo: () => {
        if (take(change.id)) release(change.id);
        // кнопку встигли натиснути, коли відповідь уже пішла: кажемо чесно, а не мовчки закриваємо
        else notify.info('Відповідь уже надіслано, скасувати не вийде');
      },
    });
    pending.current.set(change.id, { timer, variables, toast });
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
    notify.info(`${notice ? 'Вам поставили заміну' : 'Новий запит на заміну'}: ${shortTitle(change)}`);
  });

  const { clock } = useNow();
  const today = clock.date;
  const todayBells = useBells(today);
  const waiting = awaitingAnswer(changes.data ?? [], today).filter((change) => !held.has(change.id));
  const dayClock = todayBells.data ? { bells: todayBells.data.slots, minutes: clock.minutes } : null;
  // пара минула — рядок лише «Підтверджую», без заповненої кнопки: однакова ієрархія в усіх рядках
  const urgent = mostUrgent(waiting, clockNow(), (change) => changePassed(change, today, dayClock));

  // посилання з листа `/me?change={id}`: ця картка підсвічена й прокручена в поле зору (один раз)
  const [params] = useSearchParams();
  const linked = params.get('change');
  const linkedWaiting = linked !== null && waiting.some((change) => change.id === linked);
  const scrolled = useRef<string | null>(null);
  const cards = useElementRegistry();
  const { get: cardFor } = cards;
  useEffect(() => {
    if (!linkedWaiting || linked === null || scrolled.current === linked) return;
    scrolled.current = linked;
    cardFor(linked)?.scrollIntoView?.({ block: 'center' });
  }, [linked, linkedWaiting, cardFor]);

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
              <li key={change.id} id={`change-${change.id}`} ref={cards.register(change.id)} className="scroll-mt-(--sticky-top)">
                <SubstitutionCard
                  change={change}
                  primary={change.id === urgent}
                  fresh={fresh.has(change.id) || change.id === linked}
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
