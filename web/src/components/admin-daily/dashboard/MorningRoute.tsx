import { Link } from 'react-router';
import type { DashboardDto, IsoDate, OrderSummaryDto } from '@/api/types';
import { Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { buildStops, reachedCount, type StopState } from './morning';

const DOT: Record<StopState, string> = {
  done: 'border-accent bg-[var(--fill-accent)]',
  current: 'border-accent bg-surface motion-safe:animate-live',
  ahead: 'border-strong bg-surface',
};

interface MorningRouteProps {
  dashboard: DashboardDto | undefined;
  published: OrderSummaryDto | null;
  date: IsoDate;
}

/**
 * Ранок диспетчера як лінія маршруту (DESIGN: «лінія маршруту»): відсутності → рішення → підтвердження →
 * розпорядження → публікація. Пройдене — синім, поточна зупинка пульсує. Кожна зупинка веде до роботи.
 */
export function MorningRoute({ dashboard, published, date }: MorningRouteProps) {
  if (!dashboard) {
    return (
      <div className="grid gap-4 p-4 sm:grid-cols-5" aria-hidden="true">
        {[0, 1, 2, 3, 4].map((key) => (
          <div key={key} className="flex flex-col gap-2 sm:items-center">
            <Skeleton className="size-3.5 rounded-full" />
            <Skeleton className="h-7 w-10" />
            <Skeleton className="h-3.5 w-24" />
          </div>
        ))}
      </div>
    );
  }
  const stops = buildStops(dashboard, published, date);
  // синя лінія доходить до поточної зупинки: «ми вже в дорозі до неї»
  const reached = reachedCount(stops);
  const progress = (Math.min(reached, stops.length - 1) / (stops.length - 1)) * 80;
  return (
    <nav aria-label="Маршрут ранку: етапи підготовки змін" className="px-2 py-3">
      <ol className="relative grid grid-cols-1 gap-1 sm:grid-cols-5 sm:gap-0">
        <span aria-hidden="true" className="absolute top-[1.4rem] right-[10%] left-[10%] hidden h-0.5 bg-[var(--border-color-default)] sm:block" />
        <span
          aria-hidden="true"
          className="absolute top-[1.4rem] left-[10%] hidden h-0.5 bg-[var(--fill-accent)] transition-[width] duration-300 ease-out sm:block"
          style={{ width: `${progress}%` }}
        />
        {stops.map((stop) => (
          <li key={stop.id} className="relative">
            <Link
              to={stop.href}
              aria-current={stop.state === 'current' ? 'step' : undefined}
              className={cn(
                'flex h-full items-start gap-3 rounded-control px-3 py-2 sm:flex-col sm:items-center sm:gap-1.5 sm:text-center',
                'transition-[background-color] duration-100 ease-out hover:bg-hover',
                'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
              )}
            >
              <span aria-hidden="true" className={cn('relative z-10 mt-1 size-3.5 shrink-0 rounded-full border-2', DOT[stop.state])} />
              <span className="flex min-w-0 flex-col sm:items-center">
                <span
                  className={cn(
                    'font-condensed text-headline leading-none font-bold figures',
                    stop.alarm ? 'text-danger' : stop.state === 'ahead' ? 'text-secondary' : 'text-primary',
                  )}
                >
                  {stop.value}
                </span>
                <span className="mt-1.5 text-ui font-semibold text-primary">{stop.label}</span>
                <span className="text-caption text-muted">{stop.hint}</span>
              </span>
            </Link>
          </li>
        ))}
      </ol>
    </nav>
  );
}
