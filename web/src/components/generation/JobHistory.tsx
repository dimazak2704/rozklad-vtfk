import { useState } from 'react';
import { parseScore } from '@/api/generation';
import type { GenerationJobDto, Uuid } from '@/api/types';
import { Button, Panel } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/date';
import { elapsedSeconds, formatSeconds } from './model';

const STATUS: Record<GenerationJobDto['status'], { label: string; dot: string }> = {
  QUEUED: { label: 'У черзі', dot: 'bg-[var(--fill-muted)]' },
  RUNNING: { label: 'Іде', dot: 'bg-accent' },
  SOLVED: { label: 'Готово', dot: 'bg-[var(--fill-add)]' },
  FAILED: { label: 'Помилка', dot: 'bg-[var(--fill-cancel)]' },
  CANCELLED: { label: 'Скасовано', dot: 'bg-[var(--fill-muted)]' },
};

/** Скільки запусків видно одразу; решта — за «Показати всі». */
const VISIBLE = 5;

interface JobHistoryProps {
  jobs: readonly GenerationJobDto[];
  selectedId: Uuid | null;
  onSelect: (jobId: Uuid) => void;
}

/**
 * Запуски цього семестру: стан словом, коли й скільки тривав. Число жорстких порушень — лише коли
 * воно не нуль; мʼякий штраф — у самому запуску, не в кожному рядку.
 */
export function JobHistory({ jobs, selectedId, onSelect }: JobHistoryProps) {
  const [all, setAll] = useState(false);
  const selectedIndex = jobs.findIndex((job) => job.id === selectedId);
  const shown = all || selectedIndex >= VISIBLE ? jobs : jobs.slice(0, VISIBLE);
  return (
    <Panel title="Попередні запуски" flush>
      <ul className="divide-y divide-[var(--border-color-separator)]">
        {shown.map((job) => {
          const score = parseScore(job.bestScore);
          const status = STATUS[job.status];
          const hard = score ? Math.abs(score.hard) : 0;
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
                <span className="inline-flex w-24 items-center gap-2 text-ui font-[550] text-primary">
                  <span aria-hidden="true" className={cn('size-2 rounded-full', status.dot)} />
                  {status.label}
                </span>
                <span className="text-ui text-secondary">{job.startedAt ? formatInstant(job.startedAt) : 'ще не почався'}</span>
                <span className="ms-auto text-caption text-muted">
                  {formatSeconds(elapsedSeconds(job))}
                  {hard > 0 ? <span className="text-cancel"> · {hard} жорстк.</span> : null}
                </span>
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
