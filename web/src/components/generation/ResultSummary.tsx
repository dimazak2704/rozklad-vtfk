import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import type { ConstraintAnalysisDto } from '@/api/types';
import { parseScore } from '@/api/generation';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { Disclosure } from './Disclosure';
import type { ComparisonRow } from './model';

const TIMES_FORMS = ['раз', 'рази', 'разів'] as const;

/**
 * «Було / Стало» з різницею: менше — краще (зелене), більше — гірше (червоне). Коли «Було» невідоме,
 * таблиця показує лише «Стало» — без вигаданого нуля, з якого все виглядає погіршенням.
 */
export function MetricsComparison({ rows }: { rows: readonly ComparisonRow[] }) {
  const hasBefore = rows.some((row) => row.before !== null);
  return (
    <table className="w-full border-separate border-spacing-0 text-ui">
      <caption className="pb-2 text-start text-heading font-bold text-primary">{hasBefore ? 'Було і стало' : 'Що вийшло'}</caption>
      <thead>
        <tr className="text-caption text-secondary">
          <th scope="col" className="border-b border-separator py-1.5 pe-3 text-start font-[650]">
            Показник
          </th>
          {hasBefore ? (
            <th scope="col" className="border-b border-separator px-3 py-1.5 text-end font-[650]">
              Було
            </th>
          ) : null}
          <th scope="col" className="border-b border-separator px-3 py-1.5 text-end font-[650]">
            Стало
          </th>
          {hasBefore ? (
            <th scope="col" className="border-b border-separator py-1.5 ps-3 text-end font-[650]">
              Різниця
            </th>
          ) : null}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const delta = row.before === null ? 0 : row.after - row.before;
          return (
            <tr key={row.key}>
              <th scope="row" className="border-b border-separator py-2 pe-3 text-start font-normal text-primary">
                {row.label}
              </th>
              {hasBefore ? <td className="border-b border-separator px-3 py-2 text-end text-secondary figures">{row.before ?? '—'}</td> : null}
              <td className="border-b border-separator px-3 py-2 text-end font-semibold text-primary figures">{row.after}</td>
              {hasBefore ? (
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
              ) : null}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/**
 * Аналіз обмежень від розвʼязувача: що ще лишилось і скільки разів. Бали оцінки не показуємо — людині
 * вони нічого не кажуть; жорсткі обмеження позначені словом. Чисті обмеження — у розгортці.
 */
export function ConstraintAnalysis({ items }: { items: readonly ConstraintAnalysisDto[] }) {
  const active = items.filter((item) => item.matchCount > 0);
  const clean = items.filter((item) => item.matchCount === 0);
  return (
    <section aria-labelledby="analysis-title" className="flex flex-col gap-2">
      <h3 id="analysis-title" className="text-heading font-bold text-primary">
        Що лишилось
      </h3>
      {active.length === 0 ? (
        <p className="text-ui text-secondary">Жодне обмеження не порушене.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] border-y border-separator">
          {active.map((item) => {
            const score = parseScore(item.score);
            const hard = score !== null && score.hard < 0;
            return (
              <li key={item.constraint} className="flex items-baseline gap-3 py-2">
                <span
                  aria-hidden="true"
                  className={cn('size-2 shrink-0 translate-y-[-1px] rounded-full', hard ? 'bg-[var(--fill-cancel)]' : 'bg-[var(--fill-change)]')}
                />
                <span className="min-w-0 flex-1 text-ui text-primary">
                  {item.constraint}
                  {hard ? <span className="text-caption text-cancel"> · жорстке</span> : null}
                </span>
                <span className="text-caption text-secondary figures">{countLabel(item.matchCount, TIMES_FORMS)}</span>
              </li>
            );
          })}
        </ul>
      )}
      {clean.length > 0 ? (
        <Disclosure summary={`Без порушень: ${countLabel(clean.length, ['обмеження', 'обмеження', 'обмежень'])}`}>
          <ul className="flex flex-col gap-1 ps-5.5 text-caption text-secondary">
            {clean.map((item) => (
              <li key={item.constraint}>{item.constraint}</li>
            ))}
          </ul>
        </Disclosure>
      ) : null}
    </section>
  );
}
