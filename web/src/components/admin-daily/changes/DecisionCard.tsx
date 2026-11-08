import { ArrowRight, BellRing, CircleCheck, FileText, PencilLine, Undo2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { useUpdateChange } from '@/api/admin-daily';
import { useBindings, useBotStatus } from '@/api/telegram';
import { isApiError, STALE_DATA_MESSAGE } from '@/api/errors';
import type { ChangeDto, ReasonCode } from '@/api/types';
import { Button, buttonClass, notify } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDayMonth, formatInstantTime } from '@/lib/date';
import { CHANGE_KIND_LABEL, REASON_CODE_LABEL } from '@/lib/labels';
import { ChangeTimeline } from '../ChangeTimeline';
import { noticeAnswer, pendingInfo, type LessonState } from '../model';
import { StateBadge } from '../StateBadge';
import { ReasonPicker } from './ReasonPicker';

/**
 * Зміна причини вже ухваленого рішення (PUT з `version`): аудиторію лишаємо як є, викладача й дисципліну
 * так не змінити. Якщо рішення тим часом змінилося (бот, інший диспетчер), core відповідає 409 — показуємо це
 * й перечитуємо дані, а форму не закриваємо.
 */
function ReasonEditor({ change, onDone }: { change: ChangeDto; onDone: () => void }) {
  const update = useUpdateChange();
  const [code, setCode] = useState<ReasonCode>(change.reasonCode);
  const [text, setText] = useState(change.reasonText ?? '');
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    if (code === 'OTHER' && !text.trim()) {
      setError('Напишіть причину — її надрукують у розпорядженні.');
      return;
    }
    setError(null);
    try {
      await update.mutateAsync({
        id: change.id,
        body: { newRoomId: change.new.room?.id ?? null, reasonCode: code, reasonText: code === 'OTHER' ? text.trim() : null, version: change.version },
      });
      notify.success('Причину змінено');
      onDone();
    } catch (cause) {
      setError(isApiError(cause) && cause.isStaleData ? STALE_DATA_MESSAGE : isApiError(cause) ? cause.message : 'Не вдалося зберегти причину.');
    }
  };

  return (
    <div className="flex flex-col gap-2 rounded-control border border-separator bg-sunken px-3 py-2.5">
      <ReasonPicker code={code} text={text} onCodeChange={setCode} onTextChange={setText} />
      {error ? (
        <p role="alert" className="text-caption font-medium text-danger">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="primary" loading={update.isPending} onClick={() => void save()}>
          Зберегти
        </Button>
        <Button size="sm" variant="secondary" disabled={update.isPending} onClick={onDone}>
          Скасувати
        </Button>
      </div>
    </div>
  );
}

interface DecisionCardProps {
  change: ChangeDto;
  state: LessonState;
  busy: 'resend' | 'confirm' | 'cancel' | null;
  onResend: () => void;
  onConfirm: () => void;
  onCancel: () => void;
}

function Side({ label, discipline, teacher, struck }: { label: string; discipline?: string | null; teacher?: string | null; struck?: boolean }) {
  return (
    <div className="flex min-w-0 flex-col">
      <span className="text-meta font-semibold text-muted">{label}</span>
      <span
        className={cn(
          'truncate text-ui',
          struck ? 'text-muted line-through decoration-[var(--text-decoration-color-muted)]' : 'font-semibold text-primary',
        )}
      >
        {[discipline, teacher].filter(Boolean).join(' · ') || '—'}
      </span>
    </div>
  );
}

/**
 * Ухвалене рішення щодо пари: було → стало (оригінал закреслено), стан зі строком, історія і дії:
 * нагадати, підтвердити за викладача, скасувати рішення.
 */
/**
 * Чи міг запит на підтвердження дійти до замінника: notification надсилає його лише викладачеві з
 * привʼязаним Telegram і лише коли бот працює (інакше пише тільки в чат диспетчерів). `unknown` —
 * дані ще вантажаться або недоступні: тоді показуємо звичайне очікування.
 */
function useRequestDelivery(teacherId: string | undefined, enabled: boolean): 'sent' | 'noTelegram' | 'botOff' | 'unknown' {
  const bindings = useBindings();
  const bot = useBotStatus();
  if (!enabled || !teacherId) return 'unknown';
  if (bot.data && !bot.data.connected) return 'botOff';
  if (!bindings.data) return 'unknown';
  return bindings.data.some((binding) => binding.type === 'TEACHER' && binding.teacherId === teacherId) ? 'sent' : 'noTelegram';
}

export function DecisionCard({ change, state, busy, onResend, onConfirm, onCancel }: DecisionCardProps) {
  const { now } = useNow();
  const answer = noticeAnswer(change);
  const delivery = useRequestDelivery(change.new.teacher?.id, state === 'pending' || answer === 'waiting');
  const undelivered = delivery === 'noTelegram' || delivery === 'botOff';
  const pending = state === 'pending' && !undelivered ? pendingInfo(change.confirmDeadline, now.getTime()) : null;
  const substitute = change.new.teacher?.shortName ?? 'Викладач';
  const canCancel = state === 'pending' || state === 'confirmed' || state === 'cancelled' || state === 'substituteAbsent';
  const reason = change.reasonCode === 'OTHER' ? (change.reasonText ?? REASON_CODE_LABEL.OTHER) : REASON_CODE_LABEL[change.reasonCode];
  const [editingReason, setEditingReason] = useState(false);
  // core дозволяє правити відкриті й відхилені рішення; «вичитано» має лише одну причину
  const canEditReason = state !== 'applied' && state !== 'objected' && state !== 'uncovered' && change.kind !== 'DISCIPLINE_FINISHED';

  return (
    <section aria-label="Рішення щодо пари" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-2 text-ui font-semibold text-primary">
          {CHANGE_KIND_LABEL[change.kind]}
          <span className="font-normal text-muted">· причина «{reason}»</span>
        </p>
        <StateBadge state={state} size="md" />
      </div>
      {change.printedReason ? (
        <p className="-mt-2 text-caption text-secondary">
          У розпорядженні: <span className="font-semibold text-primary">«{change.printedReason}»</span>
        </p>
      ) : null}
      {editingReason ? <ReasonEditor change={change} onDone={() => setEditingReason(false)} /> : null}

      {change.kind !== 'CANCEL' ? (
        <div className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-end gap-3 rounded-control border border-separator bg-sunken px-3 py-2.5">
          <Side label="За розкладом" discipline={change.original.discipline?.shortName} teacher={change.original.teacher?.shortName} struck />
          <ArrowRight aria-label="замінено на" className="mb-0.5 size-4 text-change rtl:rotate-180" />
          <Side label="По заміні" discipline={change.new.discipline?.shortName} teacher={change.new.teacher?.shortName} />
        </div>
      ) : (
        <p className="rounded-control border border-cancel bg-cancel-subtle px-3 py-2.5 text-ui font-semibold text-cancel">
          <span className="line-through decoration-2">
            {change.original.discipline?.shortName} · {change.original.teacher?.shortName}
          </span>{' '}
          — пари не буде
        </p>
      )}

      {state === 'substituteAbsent' ? (
        <p role="alert" className="rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
          {change.new.teacher?.shortName ?? 'Замінник'} теж відсутній на цій парі. Зміну не можна підтвердити чи опублікувати:
          скасуйте її і оберіть інше рішення для пари.
        </p>
      ) : null}
      {state === 'objected' ? (
        <p role="alert" className="rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
          {substitute} не може провести цю пару, а розпорядження вже опубліковане. Додайте відсутність цього викладача
          на цю пару: пара зʼявиться в списку, і нове рішення піде в доповнення до розпорядження.
        </p>
      ) : null}
      {answer === 'waiting' && (state === 'confirmed' || state === 'applied') ? (
        <p role="status" className="rounded-control border border-separator bg-sunken px-3 py-2 text-ui text-secondary">
          {delivery === 'noTelegram'
            ? `${substitute} не підключив Telegram — повідомте про заміну самі.`
            : delivery === 'botOff'
              ? 'Telegram-бот зараз вимкнений — повідомте викладача про заміну самі.'
              : `${substitute} отримує повідомлення в Telegram. Відповіді ще немає; якщо викладач не зможе, ви побачите це тут.`}
        </p>
      ) : null}
      {answer === 'ok' ? (
        <p className="text-caption text-secondary">
          Відповідь викладача: <span className="font-semibold text-add">«Добре»</span>
          {change.respondedAt ? <span className="figures"> о {formatInstantTime(change.respondedAt)}</span> : null}.
        </p>
      ) : null}
      {state === 'pending' && undelivered ? (
        <p role="status" className="rounded-control border border-change bg-change-subtle px-3 py-2 text-ui text-change">
          {delivery === 'botOff'
            ? `Telegram-бот зараз вимкнений, тож запит ${substitute} не надіслано. Зателефонуйте й підтвердьте за викладача.`
            : `${substitute} не підключив Telegram, тож запит не надіслано. Домовтеся телефоном і підтвердьте за викладача.`}
        </p>
      ) : null}
      {pending ? (
        <p
          role="status"
          className={cn(
            'rounded-control border px-3 py-2 text-ui',
            pending.overdue ? 'border-cancel bg-danger-subtle text-danger' : 'border-change bg-change-subtle text-change',
          )}
        >
          {pending.overdue
            ? `${change.new.teacher?.shortName ?? 'Викладач'} не відповідає в Telegram: строк минув (${pending.label}). Нагадайте або підтвердьте, якщо домовилися телефоном.`
            : `Чекаємо відповіді в Telegram — ${pending.label}.`}
        </p>
      ) : null}
      {state === 'confirmed' || state === 'cancelled' ? (
        <p className="text-caption text-secondary">
          Потрапить у розпорядження на {formatDayMonth(change.date)}.{' '}
          <Link to={`/admin/orders?date=${change.date}`} className="font-semibold text-accent underline-offset-4 hover:underline">
            Переглянути чернетку
          </Link>
        </p>
      ) : null}

      <ChangeTimeline change={change} />

      {state === 'applied' || state === 'objected' ? (
        <Link to={`/admin/orders?date=${change.date}`} className={buttonClass({ variant: 'secondary', size: 'sm', className: 'self-start' })}>
          <FileText aria-hidden="true" className="size-4" />
          Відкрити розпорядження
        </Link>
      ) : (
        <div className="flex flex-wrap gap-2">
          {state === 'pending' ? (
            <>
              {undelivered ? null : (
                <Button size="sm" variant="secondary" loading={busy === 'resend'} disabled={busy !== null} onClick={onResend} iconStart={<BellRing aria-hidden="true" className="size-4" />}>
                  Нагадати в Telegram
                </Button>
              )}
              <Button
                size="sm"
                variant={undelivered ? 'primary' : 'secondary'}
                loading={busy === 'confirm'}
                disabled={busy !== null}
                onClick={onConfirm}
                iconStart={<CircleCheck aria-hidden="true" className="size-4" />}
              >
                Підтвердити за викладача
              </Button>
            </>
          ) : null}
          {state === 'declined' ? (
            // core не дає підтвердити відхилену заміну (422 CHANGE_DECLINED): викладача питаємо ще раз
            <Button size="sm" variant="secondary" loading={busy === 'resend'} disabled={busy !== null} onClick={onResend} iconStart={<BellRing aria-hidden="true" className="size-4" />}>
              Надіслати запит ще раз
            </Button>
          ) : null}
          {canEditReason && !editingReason ? (
            <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => setEditingReason(true)} iconStart={<PencilLine aria-hidden="true" className="size-4" />}>
              Змінити причину
            </Button>
          ) : null}
          {canCancel ? (
            <Button size="sm" variant="danger-ghost" loading={busy === 'cancel'} disabled={busy !== null} onClick={onCancel} iconStart={<Undo2 aria-hidden="true" className="size-4" />}>
              Скасувати рішення
            </Button>
          ) : null}
        </div>
      )}
    </section>
  );
}
