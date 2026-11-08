import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import type { ConstraintAnalysisDto } from '@/api/types';
import { parseScore } from '@/api/generation';
import { cn } from '@/lib/cn';
import type { ComparisonRow } from './model';

/** «Було / Стало» з різницею: менше — краще (зелене), більше — гірше (червоне). */
export function MetricsComparison({ rows }: { rows: readonly ComparisonRow[] }) {
  return (
    <table className="w-full border-separate border-spacing-0 text-ui">
      <caption className="pb-2 text-start text-heading font-semibold text-primary">Було / Стало</caption>
      <thead>
        <tr className="text-caption text-muted">
          <th scope="col" className="border-b border-separator py-1.5 pe-3 text-start font-semibold">
            Показник
          </th>
          <th scope="col" className="border-b border-separator px-3 py-1.5 text-end font-semibold">
            Було
          </th>
          <th scope="col" className="border-b border-separator px-3 py-1.5 text-end font-semibold">
            Стало
          </th>
          <th scope="col" className="border-b border-separator py-1.5 ps-3 text-end font-semibold">
            Різниця
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const delta = row.after - row.before;
          return (
            <tr key={row.key}>
              <th scope="row" className="border-b border-separator py-2 pe-3 text-start font-normal text-primary">
                {row.label}
              </th>
              <td className="border-b border-separator px-3 py-2 text-end text-secondary figures">{row.before}</td>
              <td className="border-b border-separator px-3 py-2 text-end font-semibold text-primary figures">{row.after}</td>
              <td className="border-b border-separator py-2 ps-3 text-end figures">
                <span
                  className={cn(
                    'inline-flex items-center justify-end gap-0.5 font-semibold',
                    delta < 0 && 'text-add',
                    delta > 0 && 'text-danger',
                    delta === 0 && 'text-muted',
                  )}
                >
                  {delta < 0 ? <ArrowDown aria-hidden="true" className="size-3.5" /> : null}
                  {delta > 0 ? <ArrowUp aria-hidden="true" className="size-3.5" /> : null}
                  {delta === 0 ? <Minus aria-hidden="true" className="size-3.5" /> : null}
                  <span className="sr-only">{delta < 0 ? 'менше на' : delta > 0 ? 'більше на' : 'без змін'}</span>
                  {delta === 0 ? null : Math.abs(delta)}
                </span>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/**
 * Аналіз обмежень від розвʼязувача: що ще лишилось і скільки воно «коштує» в оцінці. Порожні
 * обмеження (0 збігів) згорнуто в один рядок, щоб видно було головне.
 */
export function ConstraintAnalysis({ items }: { items: readonly ConstraintAnalysisDto[] }) {
  const active = items.filter((item) => item.matchCount > 0);
  const clean = items.filter((item) => item.matchCount === 0);
  return (
    <section aria-labelledby="analysis-title" className="flex flex-col gap-2">
      <h3 id="analysis-title" className="text-heading font-semibold text-primary">
        Що лишилось
      </h3>
      {active.length === 0 ? (
        <p className="text-ui text-secondary">Жодне обмеження не порушене.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-control border border-default">
          {active.map((item) => {
            const score = parseScore(item.score);
            const hard = score !== null && score.hard < 0;
            return (
              <li key={item.constraint} className="flex items-center gap-3 px-3 py-2">
                <span
                  aria-hidden="true"
                  className={cn('size-2 shrink-0 rounded-full', hard ? 'bg-[var(--fill-cancel)]' : 'bg-[var(--fill-change)]')}
                />
                <span className="min-w-0 flex-1 text-ui text-primary">
                  {item.constraint}
                  <span className="sr-only">{hard ? ' (жорстке)' : ' (мʼяке)'}</span>
                </span>
                <span className="text-caption text-secondary figures">{item.matchCount}×</span>
                <span className="w-24 text-end text-caption font-semibold text-primary figures">
                  {score ? (hard ? `${score.hard} жорст.` : `${score.soft.toLocaleString('uk-UA')}`) : item.score}
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {clean.length > 0 ? (
        <p className="text-caption text-muted">Без порушень: {clean.map((item) => item.constraint.toLocaleLowerCase('uk')).join(', ')}.</p>
      ) : null}
    </section>
  );
}
