import { useState } from 'react';
import { jobHardScore, normalizeWeights, solvedWithConflicts } from '@/api/generation';
import type { GenerationJobDto, Uuid } from '@/api/types';
import { Button, Panel } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/date';
import { elapsedSeconds, formatSeconds, jobScopeText, stopReasonLabel, stoppedManually } from './model';
import { changedPriorities } from './priorities';

const STATUS: Record<GenerationJobDto['status'], { label: string; dot: string }> = {
  QUEUED: { label: 'У черзі', dot: 'bg-[var(--fill-muted)]' },
  RUNNING: { label: 'Іде', dot: 'bg-accent' },
  SOLVED: { label: 'Готово', dot: 'bg-[var(--fill-add)]' },
  FAILED: { label: 'Помилка', dot: 'bg-[var(--fill-cancel)]' },
  CANCELLED: { label: 'Скасовано', dot: 'bg-[var(--fill-muted)]' },
};

/** SOLVED з hard < 0 — не зелене «Готово» (genfix B6). */
const WITH_CONFLICTS = { label: 'З конфліктами', dot: 'bg-[var(--fill-change)]' };

/** Зупинена вручну з чернеткою — як заголовок панелі задачі, а не «Готово». */
const STOPPED = { label: 'Зупинено', dot: 'bg-[var(--fill-add)]' };

function statusOf(job: GenerationJobDto): { label: string; dot: string } {
  if (solvedWithConflicts(job)) return WITH_CONFLICTS;
  if (stoppedManually(job)) return STOPPED;
  return STATUS[job.status];
}

/** Другий рядок запуску (genfix B8): групи, база, змінені пріоритети — щоб запуски можна було розрізнити. */
function paramsLine(job: GenerationJobDto, versionNames: ReadonlyMap<Uuid, string>): string | null {
  const scope = jobScopeText(job);
  if (!scope) return null;
  const parts = [scope];
  parts.push(job.baseVersionId ? `від «${versionNames.get(job.baseVersionId) ?? 'видаленої версії'}»` : 'з навантаження');
  const weights = normalizeWeights(job.weights);
  const changed = weights ? changedPriorities(weights).length : 0;
  // стара задача мала лише 5 правил: «змінено N» порівнювало б її з правилами, яких тоді не було
  if (job.legacyWeights) parts.push('стара версія генератора');
  else if (changed > 0) parts.push(`пріоритетів змінено: ${changed}`);
  return parts.join(' · ');
}

/** Скільки запусків видно одразу; решта — за «Показати всі». */
const VISIBLE = 5;

interface JobHistoryProps {
  jobs: readonly GenerationJobDto[];
  selectedId: Uuid | null;
  onSelect: (jobId: Uuid) => void;
  /** Назви версій для «від «…»». */
  versionNames?: ReadonlyMap<Uuid, string>;
}

/**
 * Запуски цього семестру: стан словом, коли, скільки тривав і чому зупинився. Число жорстких порушень — лише
 * коли воно не нуль; мʼякий штраф — у самому запуску, не в кожному рядку.
 */
export function JobHistory({ jobs, selectedId, onSelect, versionNames = new Map() }: JobHistoryProps) {
  const [all, setAll] = useState(false);
  const selectedIndex = jobs.findIndex((job) => job.id === selectedId);
  const shown = all || selectedIndex >= VISIBLE ? jobs : jobs.slice(0, VISIBLE);
  return (
    <Panel title="Попередні запуски" flush>
      <ul className="divide-y divide-[var(--border-color-separator)]">
        {shown.map((job) => {
          const status = statusOf(job);
          const hard = Math.abs(jobHardScore(job) ?? 0);
          const params = paramsLine(job, versionNames);
          // «Зупинено · зупинено вручну» — двічі те саме; з конфліктами причина лишається
          const reason = status === STOPPED ? null : stopReasonLabel(job);
          return (
            <li key={job.id}>
              <button
                type="button"
                aria-current={job.id === selectedId ? 'true' : undefined}
                onClick={() => onSelect(job.id)}
                className={cn(
                  'flex min-h-11 w-full flex-wrap items-center gap-x-3 gap-y-1 px-5 py-2.5 text-start transition-colors duration-100 hover-fine:bg-hover',
                  'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                  job.id === selectedId && 'bg-selected hover-fine:bg-selected',
                )}
              >
                <span className="inline-flex min-w-24 items-center gap-2 text-ui font-[550] text-primary">
                  <span aria-hidden="true" className={cn('size-2 rounded-full', status.dot)} />
                  {status.label}
                </span>
                <span className="text-ui text-secondary">{job.startedAt ? formatInstant(job.startedAt) : 'ще не почався'}</span>
                <span className="ms-auto text-caption text-muted">
                  {formatSeconds(elapsedSeconds(job))}
                  {reason ? ` · ${reason}` : null}
                  {hard > 0 ? <span className="text-cancel"> · {hard} жорстк.</span> : null}
                </span>
                {params ? <span className="basis-full text-caption text-secondary">{params}</span> : null}
              </button>
            </li>
          );
        })}
      </ul>
      {jobs.length > shown.length ? (
        <div className="border-t border-separator px-3 py-2">
          <Button variant="ghost" size="sm" onClick={() => setAll(true)}>
            Показати всі {jobs.length}
          </Button>
        </div>
      ) : null}
    </Panel>
  );
}
