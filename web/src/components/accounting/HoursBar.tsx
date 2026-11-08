import type { AccountingRow } from '@/api/accounting';
import { HoverDetails } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatNumericDate } from '@/lib/date';
import { barScale, statusLabel } from './model';

interface HoursBarProps {
  row: AccountingRow;
  className?: string;
}

const pct = (value: number, scale: number) => `${Math.max(0, Math.min(100, (value / scale) * 100))}%`;

/**
 * План / факт / прогноз однією смугою: суцільна частина — уже вичитано, світла — заплановано далі,
 * риска — план. Те, що вийде за план, бурштинове; чого не вистачить — пунктир червоним. Прапорець
 * над рискою — дата, коли години буде вичитано.
 */
export function HoursBar({ row, className }: HoursBarProps) {
  const scale = barScale(row);
  const factEnd = Math.min(row.fact, row.forecast);
  const withinPlan = Math.min(row.forecast, row.plan);
  const over = row.forecast > row.plan;
  const short = row.forecast < row.plan;
  const planShare = row.plan / scale;
  const finish = row.forecastFinishDate ? formatNumericDate(row.forecastFinishDate).slice(0, 5) : null;
  const summary = `План ${row.plan} год, вичитано ${row.fact}, ще заплановано ${row.remaining}, прогноз ${row.forecast}. ${statusLabel(row)}${
    finish ? `; план буде вичитано ${formatNumericDate(row.forecastFinishDate ?? '')}` : ''
  }.`;

  return (
    <HoverDetails
      label={`Години: ${row.discipline.shortName}`}
      content={
        <dl className="grid grid-cols-[auto_auto] gap-x-4 gap-y-1 text-ui">
          <dt className="text-secondary">План</dt>
          <dd className="text-end font-semibold figures">{row.plan} год</dd>
          <dt className="text-secondary">Вичитано</dt>
          <dd className="text-end font-semibold figures">{row.fact} год</dd>
          <dt className="text-secondary">Заплановано далі</dt>
          <dd className="text-end font-semibold figures">{row.remaining} год</dd>
          <dt className="text-secondary">Прогноз</dt>
          <dd className="text-end font-semibold figures">{row.forecast} год</dd>
          {row.forecastFinishDate ? (
            <>
              <dt className="text-secondary">План вичитано</dt>
              <dd className="text-end font-semibold figures">{formatNumericDate(row.forecastFinishDate)}</dd>
            </>
          ) : null}
        </dl>
      }
    >
      <button
        type="button"
        aria-label={summary}
        className={cn(
          'group relative block w-full min-w-40 rounded-plate pt-4 text-start',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          className,
        )}
      >
        <span aria-hidden="true" className="relative block h-3 w-full rounded-full bg-sunken ring-1 ring-[var(--border-color-separator)] ring-inset">
          {/* заплановано далі (до прогнозу, в межах плану) */}
          <span className="hatch absolute inset-y-0 start-0 rounded-full [--hatch-color:var(--border-color-accent-subtle)]" style={{ width: pct(withinPlan, scale) }} />
          {/* понад план */}
          {over ? (
            <span
              className="absolute inset-y-0 rounded-e-full bg-change-subtle ring-1 ring-[var(--border-color-change)] ring-inset"
              style={{ insetInlineStart: pct(row.plan, scale), width: pct(row.forecast - row.plan, scale) }}
            />
          ) : null}
          {/* бракує до плану */}
          {short ? (
            <span
              className="absolute inset-y-0 rounded-e-full border border-dashed border-cancel"
              style={{ insetInlineStart: pct(row.forecast, scale), width: pct(row.plan - row.forecast, scale) }}
            />
          ) : null}
          {/* вичитано */}
          <span className="absolute inset-y-0 start-0 rounded-full bg-accent transition-[width] duration-300 ease-out" style={{ width: pct(factEnd, scale) }} />
        </span>
        {/* риска плану з датою «вичитано»; біля країв підпис вирівнюємо всередину смуги */}
        <span aria-hidden="true" className="absolute top-0 bottom-0 w-0" style={{ insetInlineStart: pct(row.plan, scale) }}>
          <span
            className={cn(
              'absolute top-0 text-meta leading-none font-semibold whitespace-nowrap text-secondary figures',
              planShare > 0.8 ? 'right-0' : planShare < 0.2 ? 'left-0' : 'left-0 -translate-x-1/2',
            )}
          >
            {finish ?? `план ${row.plan}`}
          </span>
          <span className="absolute top-3.5 h-4 w-0.5 -translate-x-1/2 rounded-full bg-[var(--text-color-primary)]" />
        </span>
      </button>
    </HoverDetails>
  );
}
