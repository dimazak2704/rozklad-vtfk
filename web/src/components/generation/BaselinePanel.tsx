import type { TimetableVersionDto } from '@/api/types';
import { Panel, Tooltip } from '@/components/ui';
import { cn } from '@/lib/cn';
import { METRICS } from '@/components/timetable/model/labels';

/** Стан базової версії до запуску: що саме генератор намагатиметься покращити. */
export function BaselinePanel({ version }: { version: TimetableVersionDto }) {
  const metrics = version.metrics;
  return (
    <Panel title={`Зараз у «${version.name}»`} description="Це «Було»: після запуску тут зʼявиться «Стало» й різниця.">
      <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-control border border-default bg-[var(--border-color-separator)] sm:grid-cols-3">
        <div className="flex flex-col bg-surface px-3 py-2.5">
          <dt className="order-2 text-caption text-secondary">Жорсткі конфлікти</dt>
          <dd className={cn('order-1 font-condensed text-headline leading-none font-bold figures', version.hardConflicts > 0 ? 'text-danger' : 'text-add')}>
            {version.hardConflicts}
          </dd>
        </div>
        {METRICS.map(({ key, label, hint }) => (
          <Tooltip key={key} content={hint}>
            <div tabIndex={0} role="group" aria-label={`${label}: ${metrics ? metrics[key] : 'немає даних'}`} className="flex flex-col bg-surface px-3 py-2.5 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus">
              <dt className="order-2 text-caption text-secondary">{label}</dt>
              <dd className="order-1 font-condensed text-headline leading-none font-bold text-primary figures">{metrics ? metrics[key] : '—'}</dd>
            </div>
          </Tooltip>
        ))}
      </dl>
    </Panel>
  );
}
