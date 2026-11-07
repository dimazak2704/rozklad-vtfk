import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface PanelProps extends Omit<ComponentProps<'section'>, 'title'> {
  title?: ReactNode;
  description?: ReactNode;
  /** Дії в заголовку праворуч (кнопки, фільтри). */
  actions?: ReactNode;
  /** Рівень заголовка в документі. */
  headingLevel?: 2 | 3;
  /** Без внутрішніх відступів (таблиці, сітки до краю). */
  flush?: boolean;
  footer?: ReactNode;
}

/**
 * Робоча область на поверхні: тонка межа, без тіні (структура тримається на лініях).
 * Не вкладайте Panel у Panel — для підрозділів усередині використовуйте Section.
 */
export function Panel({
  title,
  description,
  actions,
  headingLevel = 2,
  flush = false,
  footer,
  className,
  children,
  ...props
}: PanelProps) {
  const Heading = headingLevel === 2 ? 'h2' : 'h3';
  const hasHeader = Boolean(title || actions);
  return (
    <section className={cn('min-w-0 rounded-panel border border-default bg-surface', className)} {...props}>
      {hasHeader ? (
        <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 border-b border-separator px-4 py-3">
          <div className="flex min-w-0 flex-col gap-0.5">
            {title ? <Heading className="text-heading font-semibold text-primary">{title}</Heading> : null}
            {description ? <p className="text-caption text-muted">{description}</p> : null}
          </div>
          {actions ? <div className="flex max-w-full min-w-0 flex-wrap items-center gap-2">{actions}</div> : null}
        </header>
      ) : null}
      <div className={cn(!flush && 'p-4')}>{children}</div>
      {footer ? <footer className="border-t border-separator px-4 py-3">{footer}</footer> : null}
    </section>
  );
}

export interface SectionProps extends Omit<ComponentProps<'section'>, 'title'> {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  headingLevel?: 2 | 3 | 4;
}

/** Смислова частина сторінки без рамки: заголовок, опис, вміст. */
export function Section({ title, description, actions, headingLevel = 2, className, children, ...props }: SectionProps) {
  const Heading = headingLevel === 2 ? 'h2' : headingLevel === 3 ? 'h3' : 'h4';
  return (
    <section className={cn('flex min-w-0 flex-col gap-3', className)} {...props}>
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <Heading className={cn('font-semibold text-primary', headingLevel === 2 ? 'text-title' : 'text-heading')}>
            {title}
          </Heading>
          {description ? <p className="text-ui text-secondary">{description}</p> : null}
        </div>
        {actions ? <div className="flex max-w-full min-w-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {children}
    </section>
  );
}
