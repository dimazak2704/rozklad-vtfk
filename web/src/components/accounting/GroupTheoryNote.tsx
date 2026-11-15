import { Fragment } from 'react';
import type { GroupTheoryDto } from '@/api/types';
import { Disclosure } from '@/components/generation/Disclosure';
import { cn } from '@/lib/cn';
import { graphDiffers, graphMismatchCount, graphWarning, offPeriodLabel, offPeriodTitle, theoryWeeksLabel } from './theory';

/**
 * «4КІ2: 16 тижнів теорії з 18 — НС 30.11–11.12, К 28.12–30.12». Код виду — як у графіку освітнього процесу,
 * повна назва — у підказці.
 */
export function GroupTheoryLine({ theory, className }: { theory: GroupTheoryDto; className?: string }) {
  return (
    <span className={className}>
      <span className="figures font-[650] text-primary">{theory.group.code}</span>: <span className="figures">{theoryWeeksLabel(theory)}</span>
      {theory.offPeriods.length > 0 ? ' — ' : null}
      {theory.offPeriods.map((period, index) => (
        <Fragment key={period.id ?? `${period.kind.code}-${period.startsOn}`}>
          {index > 0 ? ', ' : null}
          <abbr title={offPeriodTitle(period)} className="figures no-underline">
            {offPeriodLabel(period)}
          </abbr>
        </Fragment>
      ))}
    </span>
  );
}

/** Рядок групи над таблицею: тижні теорії й, якщо графік каже інше, тиха примітка. */
export function GroupTheoryNote({ theory, className }: { theory: GroupTheoryDto; className?: string }) {
  const warning = graphWarning(theory);
  return (
    <div className={cn('flex max-w-3xl flex-col gap-0.5', className)}>
      <p className="text-body text-secondary">
        <GroupTheoryLine theory={theory} />.
      </p>
      {warning ? <p className="text-caption text-muted">{warning}</p> : null}
    </div>
  );
}

/** Усі групи згорнутим списком: що кожну знімає з пар і де число графіка інше. */
export function GroupTheoryList({ items, className }: { items: readonly GroupTheoryDto[]; className?: string }) {
  const differing = items.filter(graphDiffers).length;
  return (
    <Disclosure
      className={className}
      summary={
        <>
          Тижні теорії груп
          {differing > 0 ? <span className="font-normal text-muted"> · {graphMismatchCount(differing)}</span> : null}
        </>
      }
    >
      <ul className="grid gap-x-8 gap-y-1 text-caption text-secondary sm:grid-cols-2 xl:grid-cols-3">
        {items.map((theory) => (
          <li key={theory.group.id}>
            <GroupTheoryLine theory={theory} />
            {graphDiffers(theory) ? <span className="figures text-muted"> · у графіку {theory.graphTheoryWeeks}</span> : null}
          </li>
        ))}
      </ul>
      {differing > 0 ? (
        <p className="mt-2 max-w-3xl text-caption text-muted">
          «У графіку» — тижні теорії з графіка освітнього процесу. Там семестр буває довшим (4 курс вчиться до лютого), тож план
          рахується за календарем системи.
        </p>
      ) : null}
    </Disclosure>
  );
}
