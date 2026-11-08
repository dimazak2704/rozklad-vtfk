import { Ban, CircleCheck, CircleDashed, FileCheck, Hourglass, UserX } from 'lucide-react';
import type { ReactNode } from 'react';
import type { IsoInstant } from '@/api/types';
import { Badge } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { LESSON_STATE, pendingInfo, type LessonState } from './model';

const ICONS: Record<LessonState, ReactNode> = {
  uncovered: <CircleDashed aria-hidden="true" className="size-3.5" />,
  declined: <UserX aria-hidden="true" className="size-3.5" />,
  substituteAbsent: <UserX aria-hidden="true" className="size-3.5" />,
  pending: <Hourglass aria-hidden="true" className="size-3.5" />,
  confirmed: <CircleCheck aria-hidden="true" className="size-3.5" />,
  cancelled: <Ban aria-hidden="true" className="size-3.5" />,
  applied: <FileCheck aria-hidden="true" className="size-3.5" />,
};

interface StateBadgeProps {
  state: LessonState;
  size?: 'sm' | 'md';
  className?: string;
}

/** Стан пари словами й іконкою; колір лише підсилює (DESIGN: «Detour Ink Rule»). */
export function StateBadge({ state, size = 'sm', className }: StateBadgeProps) {
  const meta = LESSON_STATE[state];
  return (
    <Badge tone={meta.tone} size={size} icon={ICONS[state]} className={className}>
      {meta.label}
    </Badge>
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
  return (
    <span className={cn('figures font-semibold whitespace-nowrap', info.overdue ? 'text-danger' : 'text-change', className)}>
      {info.label}
    </span>
  );
}
