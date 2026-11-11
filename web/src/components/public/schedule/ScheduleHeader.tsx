import { ErrorState, PageHeader, Skeleton } from '@/components/ui';

/** Силует заголовка, поки не відомо, чий це розклад: «‹ Розклад», код і рядок контексту. */
export function ScheduleHeaderSkeleton() {
  return (
    <div className="flex flex-col gap-3" aria-hidden="true">
      <Skeleton className="h-4 w-24" />
      <Skeleton className="h-10 w-40" />
      <Skeleton className="h-4 w-72 max-w-full" />
    </div>
  );
}

interface ScheduleLoadErrorProps {
  /** Що відкривали: код групи («4КІ1») або «Розклад викладача». */
  heading: string;
  /** Код групи чи аудиторії — великим, як на сторінці розкладу; інакше — звичайний заголовок. */
  display?: boolean;
  error: unknown;
  onRetry: () => void;
  retrying: boolean;
}

/**
 * Розклад не завантажився ще до заголовка: заголовок і «‹ Розклад» лишаються на місці, а на місці розкладу —
 * одне речення з «Спробувати ще раз». Людина бачить, де вона, і має шлях назад.
 */
export function ScheduleLoadError({ heading, display = false, error, onRetry, retrying }: ScheduleLoadErrorProps) {
  return (
    <div className="flex flex-col gap-2">
      <PageHeader size={display ? 'display' : 'headline'} title={display ? <span className="figures">{heading}</span> : heading} />
      <ErrorState error={error} title="Розклад зараз не відкривається" onRetry={onRetry} retrying={retrying} />
    </div>
  );
}
