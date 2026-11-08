import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  title: ReactNode;
  /** Що це означає і що робити далі — без «тут нічого немає». */
  description?: ReactNode;
  icon?: ReactNode;
  /** Наступна дія: кнопка або посилання. */
  action?: ReactNode;
  secondaryAction?: ReactNode;
  align?: 'start' | 'center';
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * Порожній стан: пояснює, чому порожньо, і веде до наступної дії.
 * Без ілюстрацій і «іконки в кружечку» — лише текст, тиха іконка 20 px у рядку заголовка і одна дія.
 * У робочих панелях — притиснутий ліворуч (за замовчуванням), по центру — лише на порожній сторінці.
 */
export function EmptyState({
  title,
  description,
  icon,
  action,
  secondaryAction,
  align = 'start',
  size = 'md',
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col gap-2',
        align === 'center' ? 'items-center text-center' : 'items-start text-start',
        size === 'md' ? 'px-4 py-8' : 'px-4 py-5',
        className,
      )}
    >
      <p className={cn('flex items-center gap-2 font-semibold text-primary', size === 'md' ? 'text-heading' : 'text-ui')}>
        {icon ? <span className="text-muted [&_svg]:size-5">{icon}</span> : null}
        {title}
      </p>
      {description ? (
        <p className={cn('max-w-prose text-secondary', size === 'md' ? 'text-ui' : 'text-caption')}>{description}</p>
      ) : null}
      {action || secondaryAction ? (
        <div className={cn('mt-2 flex flex-wrap gap-2', align === 'center' && 'justify-center')}>
          {action}
          {secondaryAction}
        </div>
      ) : null}
    </div>
  );
}
