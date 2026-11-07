import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Breadcrumbs, type Crumb } from './Breadcrumbs';

interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  breadcrumbs?: readonly Crumb[];
  /** Головна дія сторінки (одна заповнена кнопка) і другорядні. */
  actions?: ReactNode;
  /** Рядок під заголовком: парність, дата, статуси. */
  meta?: ReactNode;
  /** `display` — для публічних сторінок групи/викладача, `default` — робочі екрани. */
  size?: 'default' | 'display';
  className?: string;
}

/** Заголовок сторінки: єдиний h1, ланцюжок, опис і дії. */
export function PageHeader({ title, description, breadcrumbs, actions, meta, size = 'default', className }: PageHeaderProps) {
  return (
    <header className={cn('flex flex-col gap-2', className)}>
      {breadcrumbs && breadcrumbs.length > 0 ? <Breadcrumbs items={breadcrumbs} /> : null}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="flex min-w-0 flex-col gap-1.5">
          <h1
            className={cn(
              'font-semibold text-primary',
              size === 'display'
                ? 'font-condensed text-display font-bold tracking-[-0.01em]'
                : 'text-title tracking-[-0.005em]',
            )}
          >
            {title}
          </h1>
          {description ? <p className="max-w-prose text-ui text-secondary">{description}</p> : null}
          {meta ? <div className="flex flex-wrap items-center gap-2">{meta}</div> : null}
        </div>
        {actions ? <div className="flex max-w-full min-w-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
    </header>
  );
}
