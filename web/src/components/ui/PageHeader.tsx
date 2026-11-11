import { ChevronLeft } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useBackLink, type BackLink as BackTarget } from '@/hooks/useBackLink';
import { cn } from '@/lib/cn';
import { Breadcrumbs, type Crumb } from './Breadcrumbs';
import { MoreMenu, type MoreMenuItem } from './MoreMenu';

export interface PageHeaderProps {
  title: ReactNode;
  /**
   * Пояснення під заголовком. На основних екранах його немає — заголовок говорить сам;
   * дозволено лише факт про дані («Оновлено о 08:12»), а не опис функції.
   */
  description?: ReactNode;
  /** Ланцюжок замість «‹ Назад», коли рівнів більше двох: «Довідники › Групи › 4КІ1». */
  breadcrumbs?: readonly Crumb[];
  /**
   * Шлях на рівень вище. За замовчуванням — з реєстру маршрутів (`parent` у routes.ts), тож кожна
   * вкладена сторінка має «‹ Розклад» / «‹ Мій розклад» без коду на сторінці. `false` — сховати.
   */
  back?: BackTarget | false;
  /** Одна головна дія (заповнена кнопка). */
  primaryAction?: ReactNode;
  /** До двох керувань поруч: «День / Тиждень», перемикач тижня. */
  controls?: ReactNode;
  /** Усе другорядне — у меню «⋯»: поділитися, календар, QR, друк. */
  more?: readonly MoreMenuItem[];
  /** Довільні дії (старий API). Нові сторінки: `controls` + `primaryAction` + `more`. */
  actions?: ReactNode;
  /** Один рядок контексту під заголовком: «Середа, 11 листопада · ◐ чисельник». */
  meta?: ReactNode;
  /**
   * `default` — робочі екрани (22 px), `headline` — публічні сторінки й основні екрани (30 px, на телефоні 24),
   * `display` — код групи чи аудиторії як заголовок (44 px, на телефоні 34).
   */
  size?: 'default' | 'headline' | 'display';
  /** Сховати «назад» на ≥ 1024 px. Для сторінок адмінки так і є автоматично: там завжди видно бічну панель. */
  backHiddenOnDesktop?: boolean;
  className?: string;
}

const TITLE = {
  default: 'text-title font-semibold',
  headline: 'font-display text-[1.5rem] leading-[1.875rem] font-semibold tracking-[-0.02em] sm:text-headline',
  display: 'font-display text-[2.125rem] leading-[2.375rem] font-semibold tracking-[-0.015em] sm:text-display',
} as const;

/** «‹ Розклад» — тихе текстове посилання на рівень вище; ціль 44 px на дотику. */
export function BackLink({ to, label, className }: Pick<BackTarget, 'to' | 'label'> & { className?: string }) {
  return (
    <Link
      to={to}
      aria-label={`Назад: ${label}`}
      className={cn(
        '-ms-1.5 inline-flex min-h-8 w-fit items-center gap-0.5 rounded-control pe-2 ps-0.5 text-ui font-[550] text-secondary',
        className,
        'transition-colors duration-150 hover:text-primary pointer-coarse:min-h-11',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
      )}
    >
      <ChevronLeft aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
      <span>{label}</span>
    </Link>
  );
}

/**
 * Заголовок сторінки: шлях назад, єдиний h1, рядок контексту, до двох керувань, одна головна дія і «⋯».
 * Заголовок не ламається посеред фрази: дії переносяться під нього раніше, ніж h1 стискається.
 */
export function PageHeader({
  title,
  description,
  breadcrumbs,
  back,
  primaryAction,
  controls,
  more,
  actions,
  meta,
  size = 'default',
  backHiddenOnDesktop = false,
  className,
}: PageHeaderProps) {
  const auto = useBackLink();
  const target = back === false ? null : (back ?? auto);
  const hasCrumbs = Boolean(breadcrumbs && breadcrumbs.length > 0);
  const hasActions = Boolean(actions || controls || primaryAction || (more && more.length > 0));

  return (
    <header className={cn('flex flex-col gap-2', className)}>
      {hasCrumbs ? (
        <Breadcrumbs items={breadcrumbs ?? []} />
      ) : target ? (
        <div className={cn((backHiddenOnDesktop || target.sidebarShell) && 'lg:hidden')}>
          <BackLink to={target.to} label={target.label} />
        </div>
      ) : null}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        {/* заголовок бере стільки місця, скільки треба; дії переходять нижче, а не стискають його */}
        <div className="flex min-w-0 flex-[1_1_auto] flex-col gap-1">
          <h1 className={cn('min-w-0 text-primary', TITLE[size])}>{title}</h1>
          {description ? <p className="max-w-prose text-ui text-secondary">{description}</p> : null}
          {meta ? <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-ui text-secondary">{meta}</div> : null}
        </div>
        {hasActions ? (
          <div className="flex max-w-full min-w-0 flex-wrap items-center gap-2">
            {actions}
            {controls}
            {primaryAction}
            {more && more.length > 0 ? <MoreMenu items={more} /> : null}
          </div>
        ) : null}
      </div>
    </header>
  );
}
