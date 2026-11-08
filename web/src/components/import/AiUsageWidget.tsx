import { useAiUsage } from '@/api/import';
import { ErrorState, HoverDetails, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';

const usd = (value: number) => `$${value.toFixed(value < 0.1 ? 3 : 2)}`;
const tokens = (value: number) => new Intl.NumberFormat('uk-UA').format(value);

/**
 * Витрати ШІ сьогодні проти денного бюджету (AI_DAILY_BUDGET_USD). Коли бюджет вичерпано, сервер
 * відмовляє в розпізнаванні сканів і фото — тому видно заздалегідь, скільки лишилось.
 */
export function AiUsageWidget({ className }: { className?: string }) {
  const usage = useAiUsage();

  if (usage.isPending) {
    return (
      <div className={cn('flex flex-col gap-2 rounded-panel border border-default bg-surface p-4', className)} aria-busy="true">
        <Skeleton className="h-4 w-32" />
        <Skeleton className="h-2 w-full" />
        <Skeleton className="h-3 w-40" />
      </div>
    );
  }
  if (usage.isError) {
    return (
      <div className={cn('rounded-panel border border-default bg-surface', className)}>
        <ErrorState error={usage.error} onRetry={() => void usage.refetch()} size="sm" title="Витрати ШІ недоступні" />
      </div>
    );
  }

  const { today, dailyBudgetUsd, model } = usage.data;
  const share = dailyBudgetUsd > 0 ? today.costUsd / dailyBudgetUsd : 1;
  const left = Math.max(0, dailyBudgetUsd - today.costUsd);
  const tone = share >= 1 ? 'bg-[var(--fill-cancel)]' : share >= 0.8 ? 'bg-[var(--fill-change)]' : 'bg-accent';

  return (
    <section aria-label="Витрати ШІ сьогодні" className={cn('flex flex-col gap-2.5 rounded-panel border border-default bg-surface p-4', className)}>
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="text-ui font-semibold text-primary">ШІ сьогодні</h2>
        <HoverDetails
          label="Моделі ШІ"
          content={
            <dl className="grid grid-cols-[auto_auto] gap-x-4 gap-y-1 text-caption">
              <dt className="text-secondary">Текст</dt>
              <dd className="font-mono text-primary">{model.text}</dd>
              <dt className="text-secondary">Сканування й фото</dt>
              <dd className="font-mono text-primary">{model.vision}</dd>
              <dt className="text-secondary">Токени сьогодні</dt>
              <dd className="text-primary figures">
                {tokens(today.inputTokens)} вхід · {tokens(today.outputTokens)} вихід
              </dd>
            </dl>
          }
        >
          <button
            type="button"
            className="rounded-plate text-caption font-semibold text-accent underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
          >
            Моделі
          </button>
        </HoverDetails>
      </div>
      <p className="flex items-baseline gap-1.5">
        <span className="font-condensed text-title font-bold figures text-primary">{usd(today.costUsd)}</span>
        <span className="text-caption text-muted figures">з {usd(dailyBudgetUsd)}</span>
      </p>
      <div
        role="meter"
        aria-label="Використано денного бюджету ШІ"
        aria-valuemin={0}
        aria-valuemax={dailyBudgetUsd}
        aria-valuenow={today.costUsd}
        aria-valuetext={`${usd(today.costUsd)} з ${usd(dailyBudgetUsd)}`}
        className="relative h-2 overflow-hidden rounded-full bg-sunken"
      >
        <span className={cn('absolute inset-y-0 start-0 rounded-full transition-[width] duration-300 ease-out', tone)} style={{ width: `${Math.min(100, share * 100)}%` }} />
      </div>
      <p className="text-caption text-secondary figures">
        {today.calls === 0
          ? 'Сьогодні ШІ ще не викликали.'
          : share >= 1
            ? 'Бюджет вичерпано: скани й фото розпізнаємо завтра.'
            : `${countLabel(today.calls, ['виклик', 'виклики', 'викликів'])} · лишилось ${usd(left)}`}
      </p>
    </section>
  );
}
