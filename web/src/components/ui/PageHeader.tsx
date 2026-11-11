import { ChevronLeft } from 'lucide-react';
import { useLayoutEffect, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useBackLink, type BackLink as BackTarget } from '@/hooks/useBackLink';
import { cn } from '@/lib/cn';
import { Breadcrumbs, type Crumb } from './Breadcrumbs';
import { useErrorScope } from './error-scope';
import { MoreMenu, type MoreMenuItem } from './MoreMenu';
import { PageErrorBanner } from './PageErrors';

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
   * `default` і `headline` — заголовок сторінки (30 px, на телефоні 24): він завжди найбільший текст
   * робочої області, а заголовки розділів (22 px) — на крок нижче.
   * `display` — код групи чи аудиторії як заголовок (44 px, на телефоні 34).
   */
  size?: 'default' | 'headline' | 'display';
  /** Сховати «назад» на ≥ 1024 px. Для сторінок адмінки так і є автоматично: там завжди видно бічну панель. */
  backHiddenOnDesktop?: boolean;
  className?: string;
}

const HEADLINE = 'font-display text-[1.5rem] leading-[1.875rem] font-semibold tracking-[-0.02em] sm:text-headline';

const TITLE = {
  default: HEADLINE,
  headline: HEADLINE,
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
 *
 * Розкладка не стрибає між сторінками: заголовок і дії вирівняні за верхнім краєм, тож h1 стоїть на тій
 * самій висоті з підзаголовком чи без, з кнопкою будь-якої висоти. Головна дія і «⋯» — одна нерозривна
 * група: «⋯» ніколи не переноситься сам. Вужче 1024 px другорядні керування (версія, «Сітка / Порівняння»,
 * семестр) стають окремим рядком під заголовком, а заголовок, головна дія й «⋯» лишаються в першому.
 * Заголовок не ламається посеред фрази: дії переносяться під нього раніше, ніж h1 стискається.
 *
 * Усередині `PageErrorScope` під заголовком зʼявляється один рядок помилки сторінки.
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
  const scope = useErrorScope();
  const register = scope?.registerHeader;
  useLayoutEffect(() => register?.(), [register]);
  const failure = scope?.failure ?? null;
  const target = back === false ? null : (back ?? auto);
  const hasCrumbs = Boolean(breadcrumbs && breadcrumbs.length > 0);
  const secondary = Boolean(actions || controls);
  const hasMore = Boolean(more && more.length > 0);
  const cluster = Boolean(primaryAction || hasMore);
  const iconOnly = hasMore && !primaryAction;

  return (
    <header className={cn('flex flex-col gap-2', className)}>
      {hasCrumbs ? (
        <Breadcrumbs items={breadcrumbs ?? []} />
      ) : target ? (
        <div className={cn((backHiddenOnDesktop || target.sidebarShell) && 'lg:hidden')}>
          <BackLink to={target.to} label={target.label} />
        </div>
      ) : null}
      <div
        className={cn(
          iconOnly
            ? // лише «⋯»: сітка — він у першому рядку поруч із h1, а підзаголовок і рядок контексту на всю ширину
              'grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-3 gap-y-1 lg:flex lg:flex-wrap lg:justify-between lg:gap-x-6 lg:gap-y-3'
            : 'flex flex-wrap items-start justify-between gap-x-6 gap-y-3',
          // рядок не нижчий за кнопку (40 px): з дією чи без неї вкладки під заголовком стоять на тій самій висоті
          'sm:min-h-10',
        )}
      >
        {/* заголовок бере стільки місця, скільки треба; дії переходять нижче, а не стискають його */}
        <div
          className={cn(
            iconOnly ? 'contents lg:flex lg:min-w-0 lg:flex-[1_1_auto] lg:flex-col lg:gap-1' : 'flex min-w-0 flex-[1_1_auto] flex-col gap-1',
          )}
        >
          <h1 className={cn('col-start-1 row-start-1 min-w-0 text-primary', TITLE[size])}>{title}</h1>
          {description ? <p className="col-span-2 max-w-prose text-ui text-secondary">{description}</p> : null}
          {meta ? <div className="col-span-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-ui text-secondary">{meta}</div> : null}
        </div>
        {secondary || cluster ? (
          // вужче 1024 обгортка «зникає» (contents): керування — окремим рядком, група дій — поруч із заголовком
          <div className="contents lg:flex lg:max-w-full lg:min-w-0 lg:flex-wrap lg:items-center lg:justify-end lg:gap-2">
            {secondary ? (
              <div
                className={cn(
                  'flex max-w-full min-w-0 flex-wrap items-center gap-2',
                  cluster && 'order-last basis-full lg:order-none lg:basis-auto',
                  iconOnly && 'col-span-2 mt-2 lg:mt-0',
                )}
              >
                {actions}
                {controls}
              </div>
            ) : null}
            {cluster ? (
              <div className="col-start-2 row-start-1 flex shrink-0 items-center gap-2">
                {primaryAction}
                {hasMore ? <MoreMenu items={more ?? []} /> : null}
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
      {failure ? (
        <PageErrorBanner
          error={failure.error}
          title={failure.title}
          retrying={failure.retrying}
          onRetry={failure.onRetry}
          className="mt-4"
        />
      ) : null}
    </header>
  );
}
