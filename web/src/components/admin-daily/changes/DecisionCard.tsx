import { BellRing, CircleCheck, FileText, PencilLine, Undo2 } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useUpdateChange } from '@/api/admin-daily';
import { useBindings, useBotStatus } from '@/api/telegram';
import { isApiError, STALE_DATA_MESSAGE } from '@/api/errors';
import type { ChangeDto, ReasonCode } from '@/api/types';
import { Button, buttonClass, MoreMenu, notify, type MoreMenuItem } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDayMonth, formatInstantTime } from '@/lib/date';
import { REASON_CODE_LABEL } from '@/lib/labels';
import { ChangeTimeline } from '../ChangeTimeline';
import { noticeAnswer, pendingInfo, type LessonState } from '../model';
import { StateText } from '../StateBadge';
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
    <div className="flex flex-col gap-2 rounded-control border border-separator bg-surface px-3 py-2.5">
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

/** «СТП · Кренцін» — сторона зміни без висячих розділювачів. */
function side(part: ChangeDto['original']): string {
  return [part.discipline?.shortName, part.teacher?.shortName].filter(Boolean).join(' · ') || '—';
}

const WORD: Record<LessonState, string> = {
  uncovered: 'Потрібне рішення',
  declined: 'Відмова',
  substituteAbsent: 'Замінник відсутній',
  objected: 'Потрібне доповнення',
  pending: 'Чекає відповіді',
  confirmed: 'Підтверджено',
  cancelled: 'Пари не буде',
  applied: 'У розпорядженні',
};

type Sentence = { text: ReactNode; tone: 'plain' | 'warn' | 'alert' };

/**
 * Ухвалене рішення щодо пари: стан словом, «було → стало» (старе закреслено), одне речення про те, що
 * далі, і до двох дій; рідші дії (змінити причину, скасувати рішення) — у «⋯». Історія — на вимогу.
 */
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

  // одне речення про те, що далі (найважливіше з можливих)
  let sentence: Sentence | null = null;
  if (state === 'substituteAbsent') {
    sentence = { tone: 'alert', text: `${substitute} теж відсутній на цій парі. Скасуйте рішення й оберіть інше.` };
  } else if (state === 'objected') {
    sentence = {
      tone: 'alert',
      text: `${substitute} не може провести пару, а розпорядження вже опубліковане. Додайте відсутність цього викладача на цю пару — нове рішення піде в доповнення.`,
    };
  } else if (state === 'pending' && undelivered) {
    sentence = {
      tone: 'warn',
      text:
        delivery === 'botOff'
          ? `Telegram-бот вимкнений, тож запит ${substitute} не надіслано. Зателефонуйте й підтвердьте за викладача.`
          : `${substitute} не підключив Telegram, тож запит не надіслано. Домовтеся телефоном і підтвердьте за викладача.`,
    };
  } else if (pending) {
    sentence = pending.overdue
      ? { tone: 'alert', text: `${substitute} не відповідає: строк минув (${pending.label}). Нагадайте або підтвердьте, якщо домовилися телефоном.` }
      : { tone: 'plain', text: `Чекаємо відповіді в Telegram — ${pending.label}.` };
  } else if (state === 'declined') {
    sentence = { tone: 'plain', text: 'Оберіть інший варіант нижче або надішліть запит ще раз.' };
  } else if (answer === 'waiting' && (state === 'confirmed' || state === 'applied')) {
    sentence = {
      tone: 'plain',
      text:
        delivery === 'noTelegram'
          ? `${substitute} не підключив Telegram — повідомте про заміну самі.`
          : delivery === 'botOff'
            ? 'Telegram-бот зараз вимкнений — повідомте викладача про заміну самі.'
            : `${substitute} отримує повідомлення в Telegram. Якщо не зможе, ви побачите це тут.`,
    };
  } else if (answer === 'ok') {
    sentence = {
      tone: 'plain',
      text: (
        <>
          Викладач відповів «Добре»{change.respondedAt ? <span className="figures"> о {formatInstantTime(change.respondedAt)}</span> : null}.
        </>
      ),
    };
  } else if (state === 'confirmed' || state === 'cancelled') {
    sentence = {
      tone: 'plain',
      text: (
        <>
          Потрапить у розпорядження на {formatDayMonth(change.date)}.{' '}
          <Link to={`/admin/orders?date=${change.date}`} className="font-semibold text-accent underline-offset-4 hover:underline">
            Переглянути чернетку
          </Link>
        </>
      ),
    };
  }

  const more: MoreMenuItem[] = [];
  if (canEditReason && !editingReason) {
    more.push({ label: 'Змінити причину', icon: <PencilLine aria-hidden="true" />, onSelect: () => setEditingReason(true) });
  }
  if (canCancel && state !== 'substituteAbsent') {
    more.push({ label: 'Скасувати рішення', icon: <Undo2 aria-hidden="true" />, onSelect: onCancel, tone: 'danger', separatorBefore: more.length > 0 });
  }

  return (
    <section aria-label="Рішення щодо пари" className="flex flex-col gap-3 rounded-popover bg-sunken px-4 py-3.5">
      <div className="flex min-h-9 items-center justify-between gap-2">
        <StateText state={state} className="text-ui">
          {WORD[state]}
        </StateText>
        {more.length > 0 ? <MoreMenu items={more} label="Ще дії з рішенням" /> : null}
      </div>

      {change.kind !== 'CANCEL' ? (
        <p className="flex flex-wrap items-baseline gap-x-2 text-cell">
          <s className="text-muted decoration-[var(--fill-muted)]">{side(change.original)}</s>
          <span aria-hidden="true" className="text-muted">
            →
          </span>
          <span className="sr-only">замінено на</span>
          <span className="font-[650] text-primary">{side(change.new)}</span>
        </p>
      ) : (
        <p className="text-cell">
          <s className="text-muted decoration-[var(--fill-cancel)]">{side(change.original)}</s>{' '}
          <span className="font-[650] text-cancel">— пари не буде</span>
        </p>
      )}

      {sentence ? (
        <p
          role={sentence.tone === 'plain' ? 'status' : 'alert'}
          className={cn('text-ui', sentence.tone === 'alert' ? 'text-danger' : sentence.tone === 'warn' ? 'text-change' : 'text-secondary')}
        >
          {sentence.text}
        </p>
      ) : null}

      <p className="text-caption text-muted">
        У розпорядженні: <span className="text-secondary">«{change.printedReason ?? reason}»</span>
      </p>
      {editingReason ? <ReasonEditor change={change} onDone={() => setEditingReason(false)} /> : null}

      {change.history.length > 0 ? (
        <details>
          <summary className="inline-flex min-h-8 cursor-pointer list-none items-center rounded-tag text-caption font-semibold text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11 [&::-webkit-details-marker]:hidden">
            Історія рішення
          </summary>
          <ChangeTimeline change={change} className="mt-2" />
        </details>
      ) : null}

      {state === 'applied' || state === 'objected' ? (
        <Link to={`/admin/orders?date=${change.date}`} className={buttonClass({ variant: 'secondary', size: 'sm', className: 'self-start' })}>
          <FileText aria-hidden="true" className="size-4" />
          Відкрити розпорядження
        </Link>
      ) : state === 'pending' || state === 'declined' || state === 'substituteAbsent' ? (
        <div className="flex flex-wrap gap-2">
          {state === 'pending' && !undelivered ? (
            <Button size="sm" variant="secondary" loading={busy === 'resend'} disabled={busy !== null} onClick={onResend} iconStart={<BellRing aria-hidden="true" className="size-4" />}>
              Нагадати в Telegram
            </Button>
          ) : null}
          {state === 'pending' ? (
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
          ) : null}
          {state === 'declined' ? (
            // core не дає підтвердити відхилену заміну (422 CHANGE_DECLINED): викладача питаємо ще раз
            <Button size="sm" variant="secondary" loading={busy === 'resend'} disabled={busy !== null} onClick={onResend} iconStart={<BellRing aria-hidden="true" className="size-4" />}>
              Надіслати запит ще раз
            </Button>
          ) : null}
          {state === 'substituteAbsent' ? (
            <Button size="sm" variant="secondary" loading={busy === 'cancel'} disabled={busy !== null} onClick={onCancel} iconStart={<Undo2 aria-hidden="true" className="size-4" />}>
              Скасувати рішення
            </Button>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
