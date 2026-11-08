import type { ImportJob } from '@/api/import';
import { isBusy } from '@/api/import';
import type { ImportJobStatus } from '@/api/types';
import { Badge, Spinner } from '@/components/ui';
import { countLabel } from '@/lib/labels';
import { IMPORT_STATUS_LABEL, IMPORT_STATUS_TONE } from './labels';

/** Стан завдання: для тих, що в роботі, — спінер (і читач екрана чує зміну через aria-live списку). */
export function JobStatusBadge({ status }: { status: ImportJobStatus }) {
  return (
    <Badge tone={IMPORT_STATUS_TONE[status]} size="sm" icon={isBusy(status) ? <Spinner size="sm" label="у роботі" /> : undefined}>
      {IMPORT_STATUS_LABEL[status]}
    </Badge>
  );
}

/** «412 розпізнано · 4 чекають рішення · 7 попереджень» — коротко, що в звіті. */
export function JobSummary({ job }: { job: Pick<ImportJob, 'status' | 'summary' | 'error'> }) {
  if (job.status === 'FAILED') return <span className="line-clamp-2 text-caption text-danger" title={job.error ?? undefined}>{job.error ?? 'Файл не вдалося розібрати.'}</span>;
  if (isBusy(job.status) && job.summary.recognized === 0) return <span className="text-caption text-muted">Звіт ще готується…</span>;
  const { recognized, unknown, conflicts, warnings } = job.summary;
  return (
    <span className="flex flex-wrap gap-x-2 text-caption text-secondary figures">
      <span>{recognized} розпізнано</span>
      {conflicts > 0 ? <span className="font-semibold text-change">{conflicts} чекають рішення</span> : null}
      {unknown > 0 ? <span>{countLabel(unknown, ['новий', 'нові', 'нових'])}</span> : null}
      {warnings > 0 ? <span>{countLabel(warnings, ['попередження', 'попередження', 'попереджень'])}</span> : null}
    </span>
  );
}
