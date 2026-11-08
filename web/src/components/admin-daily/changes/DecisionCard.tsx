import { ArrowRight, BellRing, CircleCheck, FileText, Undo2 } from 'lucide-react';
import { Link } from 'react-router';
import type { ChangeDto } from '@/api/types';
import { Button, buttonClass } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDayMonth } from '@/lib/date';
import { CHANGE_KIND_LABEL, REASON_CODE_LABEL } from '@/lib/labels';
import { ChangeTimeline } from '../ChangeTimeline';
import { pendingInfo, type LessonState } from '../model';
import { StateBadge } from '../StateBadge';

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
export function DecisionCard({ change, state, busy, onResend, onConfirm, onCancel }: DecisionCardProps) {
  const { now } = useNow();
  const pending = state === 'pending' ? pendingInfo(change.confirmDeadline, now.getTime()) : null;
  const canCancel = state === 'pending' || state === 'confirmed' || state === 'cancelled';
  const reason = change.reasonCode === 'OTHER' ? (change.reasonText ?? REASON_CODE_LABEL.OTHER) : REASON_CODE_LABEL[change.reasonCode];

  return (
    <section aria-label="Рішення щодо пари" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-2 text-ui font-semibold text-primary">
          {CHANGE_KIND_LABEL[change.kind]}
          <span className="font-normal text-muted">· причина «{reason}»</span>
        </p>
        <StateBadge state={state} size="md" />
      </div>

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

      {state === 'applied' ? (
        <Link to={`/admin/orders?date=${change.date}`} className={buttonClass({ variant: 'secondary', size: 'sm', className: 'self-start' })}>
          <FileText aria-hidden="true" className="size-4" />
          Відкрити розпорядження
        </Link>
      ) : (
        <div className="flex flex-wrap gap-2">
          {state === 'pending' ? (
            <>
              <Button size="sm" variant="secondary" loading={busy === 'resend'} disabled={busy !== null} onClick={onResend} iconStart={<BellRing aria-hidden="true" className="size-4" />}>
                Нагадати в Telegram
              </Button>
              <Button size="sm" variant="secondary" loading={busy === 'confirm'} disabled={busy !== null} onClick={onConfirm} iconStart={<CircleCheck aria-hidden="true" className="size-4" />}>
                Підтвердити за викладача
              </Button>
            </>
          ) : null}
          {state === 'declined' ? (
            <Button size="sm" variant="secondary" loading={busy === 'confirm'} disabled={busy !== null} onClick={onConfirm} iconStart={<CircleCheck aria-hidden="true" className="size-4" />}>
              Домовилися телефоном — підтвердити
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
