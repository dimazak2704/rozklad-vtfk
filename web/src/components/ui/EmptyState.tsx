import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  /** Одне речення для цієї ситуації: «Сьогодні замін немає.», «На четвер ніхто не відсутній.» */
  title: ReactNode;
  /** Що далі — лише коли це не очевидно з дії. */
  description?: ReactNode;
  /** Тиха іконка 20 px у рядку речення (необовʼязково). */
  icon?: ReactNode;
  /** Одна наступна дія — зазвичай ghost-кнопка або посилання. */
  action?: ReactNode;
  secondaryAction?: ReactNode;
  align?: 'start' | 'center';
  size?: 'sm' | 'md';
  /** Внутрішній відступ панелі (16 px) — коли стан стоїть просто в рамці картки, а не в Panel. */
  inset?: boolean;
  className?: string;
}

/**
 * Порожній стан — спокійне речення, а не «ілюстрація в коробці»: без рамки, пунктиру й водяного знака.
 * Пояснює, чому порожньо, і веде до однієї наступної дії. У робочих панелях — ліворуч, урівень із
 * заголовком панелі; по центру — лише на порожній сторінці.
 */
export function EmptyState({
  title,
  description,
  icon,
  action,
  secondaryAction,
  align = 'start',
  size = 'md',
  inset = false,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col gap-1',
        align === 'center' ? 'items-center px-4 text-center' : 'items-start text-start',
        inset && align === 'start' && 'px-4',
        size === 'md' ? 'py-6' : 'py-4',
        className,
      )}
    >
      <p className={cn('flex items-center gap-2 text-pretty text-primary', size === 'md' ? 'text-body font-[650]' : 'text-ui font-[650]')}>
        {icon ? <span className="text-muted [&_svg]:size-5">{icon}</span> : null}
        {title}
      </p>
      {description ? (
        <p className={cn('max-w-prose text-pretty text-secondary', size === 'md' ? 'text-ui' : 'text-caption')}>{description}</p>
      ) : null}
      {action || secondaryAction ? (
        <div className={cn('mt-3 flex flex-wrap gap-2', align === 'center' && 'justify-center')}>
          {action}
          {secondaryAction}
        </div>
      ) : null}
    </div>
  );
}
