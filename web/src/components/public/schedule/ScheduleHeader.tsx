import type { ReactNode } from 'react';
import type { WeekParity } from '@/api/types';
import { ParityBadge, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';

interface ScheduleHeaderProps {
  /** «Розклад групи», «Розклад викладача», «Аудиторія». */
  kindLabel: string;
  /** Код групи, прізвище, номер аудиторії — великим конденсованим шрифтом, як номер маршруту. */
  title: ReactNode;
  /** Рядок під назвою: курс і спеціальність, циклова комісія, тип аудиторії. */
  details?: ReactNode;
  parity?: WeekParity | null;
  weekNumber?: number | null;
  /** Додаткові позначки поруч із парністю (зміна, підгрупи). */
  badges?: ReactNode;
  actions?: ReactNode;
  className?: string;
}

/** Заголовок сторінки розкладу: єдиний h1 «Розклад групи 4КІ1», парність тижня на екрані, дії. */
export function ScheduleHeader({ kindLabel, title, details, parity, weekNumber, badges, actions, className }: ScheduleHeaderProps) {
  return (
    <header className={cn('flex flex-wrap items-end justify-between gap-x-6 gap-y-4', className)}>
      <div className="flex min-w-0 flex-col gap-2">
        <h1 className="flex flex-col gap-1">
          <span className="text-ui font-semibold text-secondary">{kindLabel}</span>{' '}
          <span className="font-condensed text-display leading-none font-bold tracking-[-0.01em] break-words text-primary">
            {title}
          </span>
        </h1>
        {details ? <p className="max-w-prose text-ui text-secondary">{details}</p> : null}
        {parity || badges ? (
          <div className="flex flex-wrap items-center gap-2">
            {parity ? <ParityBadge parity={parity} weekNumber={weekNumber ?? undefined} size="sm" /> : null}
            {badges}
          </div>
        ) : null}
      </div>
      {actions}
    </header>
  );
}

/** Силует заголовка, поки не відомо, чий це розклад. */
export function ScheduleHeaderSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-hidden="true">
      <Skeleton className="h-4 w-28" />
      <Skeleton className="h-10 w-40" />
      <Skeleton className="h-4 w-72 max-w-full" />
    </div>
  );
}
