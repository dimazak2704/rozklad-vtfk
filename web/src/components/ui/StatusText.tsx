import { CircleAlert, CircleCheck, CircleX, Clock3, FilePen, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * Стан документа чи підтвердження — іконка й слово, без «таблички».
 * Одна ідіома на одне значення (DESIGN.md § Бейджі): чернетка, чекає, підтверджено/опубліковано, відхилено, помилка.
 */
export type StatusKind = 'draft' | 'pending' | 'done' | 'rejected' | 'failed';

const KIND: Record<StatusKind, { icon: LucideIcon; className: string }> = {
  draft: { icon: FilePen, className: 'text-secondary' },
  pending: { icon: Clock3, className: 'text-secondary' },
  done: { icon: CircleCheck, className: 'text-add' },
  rejected: { icon: CircleX, className: 'text-danger' },
  failed: { icon: CircleAlert, className: 'text-danger' },
};

interface StatusTextProps {
  kind: StatusKind;
  children: ReactNode;
  className?: string;
}

/** «✓ Опубліковано»: іконка 16 px і слово 14/500 одним кольором стану. */
export function StatusText({ kind, children, className }: StatusTextProps) {
  const { icon: Icon, className: tone } = KIND[kind];
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-ui font-medium whitespace-nowrap', tone, className)}>
      <Icon aria-hidden="true" className="size-4" />
      {children}
    </span>
  );
}
