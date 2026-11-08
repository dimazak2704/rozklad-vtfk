import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Breadcrumbs, type Crumb } from './Breadcrumbs';

interface PageHeaderProps {
  title: ReactNode;
  /**
   * Пояснення під заголовком. На основних екранах його немає — заголовок говорить сам;
   * лишається для складних екранів (генератор, імпорт).
   */
  description?: ReactNode;
  breadcrumbs?: readonly Crumb[];
  /** Головна дія сторінки (одна заповнена кнопка) і другорядні. */
  actions?: ReactNode;
  /** Рядок даних під заголовком: «пн, 9 листопада · чисельник · 4 пари · 1 заміна». */
  meta?: ReactNode;
  /**
   * `default` — h1 робочих екранів (22 px), `headline` — публічні сторінки (28 px, на телефоні 24 px),
   * `display` — код групи чи аудиторії як заголовок (Condensed 40 px).
   */
  size?: 'default' | 'headline' | 'display';
  className?: string;
}

const TITLE = {
  default: 'text-title font-semibold',
  headline: 'text-[1.5rem] leading-[1.875rem] font-semibold tracking-[-0.01em] sm:text-headline',
  display: 'font-condensed text-display font-bold tracking-[-0.01em]',
} as const;

/** Заголовок сторінки: єдиний h1, ланцюжок, рядок даних і дії. */
export function PageHeader({ title, description, breadcrumbs, actions, meta, size = 'default', className }: PageHeaderProps) {
  return (
    <header className={cn('flex flex-col gap-2', className)}>
      {breadcrumbs && breadcrumbs.length > 0 ? <Breadcrumbs items={breadcrumbs} /> : null}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className={cn('text-primary', TITLE[size])}>{title}</h1>
          {description ? <p className="max-w-prose text-ui text-secondary">{description}</p> : null}
          {meta ? <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-ui text-secondary figures">{meta}</div> : null}
        </div>
        {actions ? <div className="flex max-w-full min-w-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
    </header>
  );
}
