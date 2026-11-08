import type { GenerationJobView } from '@/api/generation';
import { parseScore } from '@/api/generation';
import type { Uuid } from '@/api/types';
import { Badge, Panel, type BadgeTone } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/date';
import { elapsedSeconds, formatSeconds } from './model';

const STATUS: Record<GenerationJobView['status'], { label: string; tone: BadgeTone }> = {
  QUEUED: { label: 'У черзі', tone: 'neutral' },
  RUNNING: { label: 'Іде', tone: 'accent' },
  SOLVED: { label: 'Готово', tone: 'success' },
  FAILED: { label: 'Помилка', tone: 'danger' },
  CANCELLED: { label: 'Скасовано', tone: 'neutral' },
};

interface JobHistoryProps {
  jobs: readonly GenerationJobView[];
  selectedId: Uuid | null;
  onSelect: (jobId: Uuid) => void;
}

/** Запуски цього семестру: стан, коли, скільки тривав і з якою оцінкою — клік відкриває результат. */
export function JobHistory({ jobs, selectedId, onSelect }: JobHistoryProps) {
  return (
    <Panel title="Попередні запуски" flush>
      <ul className="divide-y divide-[var(--border-color-separator)]">
        {jobs.map((job) => {
          const score = parseScore(job.bestScore);
          const status = STATUS[job.status];
          return (
            <li key={job.id}>
              <button
                type="button"
                aria-current={job.id === selectedId ? 'true' : undefined}
                onClick={() => onSelect(job.id)}
                className={cn(
                  'flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-start transition-colors duration-100 hover:bg-hover',
                  'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                  job.id === selectedId && 'bg-selected hover:bg-selected-hover',
                )}
              >
                <Badge tone={status.tone} size="sm">
                  {status.label}
                </Badge>
                <span className="text-ui text-primary figures">{job.startedAt ? formatInstant(job.startedAt) : 'ще не почався'}</span>
                <span className="ms-auto text-caption text-secondary figures">
                  {formatSeconds(elapsedSeconds(job))}
                  {score ? ` · жорстких ${Math.abs(score.hard)} · штраф ${Math.abs(score.soft).toLocaleString('uk-UA')}` : ''}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}
