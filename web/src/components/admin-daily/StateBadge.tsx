import type { ReactNode } from 'react';
import type { IsoInstant } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { LESSON_STATE, pendingInfo, type LessonState } from './model';

/** Крапка стану: колір лише підсилює слово (стан читається й у відтінках сірого). */
const DOT: Record<LessonState, string> = {
  uncovered: 'bg-[var(--fill-change)]',
  declined: 'bg-[var(--fill-cancel)]',
  substituteAbsent: 'bg-[var(--fill-cancel)]',
  objected: 'bg-[var(--fill-cancel)]',
  pending: 'border-[1.5px] border-[var(--fill-change)] bg-transparent',
  confirmed: 'bg-[var(--fill-add)]',
  cancelled: 'bg-[var(--fill-cancel)]',
  applied: 'bg-[var(--fill-accent)]',
};

/** Колір слова: лише стани, що чекають дії, підсвічені; решта — звичайним текстом. */
const WORD: Record<LessonState, string> = {
  uncovered: 'text-change',
  declined: 'text-cancel',
  substituteAbsent: 'text-cancel',
  objected: 'text-cancel',
  pending: 'text-secondary',
  confirmed: 'text-secondary',
  cancelled: 'text-cancel',
  applied: 'text-secondary',
};

interface StateTextProps {
  state: LessonState;
  /** Своє речення замість слова стану: «Кренцін проведе СТП · підтвердив». */
  children?: ReactNode;
  className?: string;
}

/** Стан пари — крапка й слово (DESIGN §7: стани пар ніколи не «чипи»). */
export function StateText({ state, children, className }: StateTextProps) {
  return (
    <span className={cn('inline-flex min-w-0 items-start gap-2 text-caption font-[550]', WORD[state], className)}>
      {/* крапка — по центру першого рядка, навіть коли речення переноситься */}
      <span aria-hidden="true" className={cn('mt-[calc(0.5lh-4px)] inline-block size-2 shrink-0 rounded-full', DOT[state])} />
      <span className="min-w-0">{children ?? LESSON_STATE[state].label}</span>
    </span>
  );
}

interface PendingCountdownProps {
  deadline: IsoInstant | null | undefined;
  className?: string;
}

/** «ще 11 хв» до строку відповіді або «прострочено 7 хв» — оновлюється разом зі спільним годинником. */
export function PendingCountdown({ deadline, className }: PendingCountdownProps) {
  const { now } = useNow();
  const info = pendingInfo(deadline, now.getTime());
  if (!info) return null;
  return <span className={cn('whitespace-nowrap figures', info.overdue ? 'text-danger' : 'text-secondary', className)}>{info.label}</span>;
}
