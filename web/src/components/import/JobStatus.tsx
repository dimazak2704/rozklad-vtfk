import type { ImportJob } from '@/api/import';
import { isBusy } from '@/api/import';
import type { ImportJobStatus } from '@/api/types';
import { Spinner } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { IMPORT_STATUS_LABEL, summarySentence } from './labels';

const STATUS_DOT: Record<ImportJobStatus, string> = {
  UPLOADED: 'bg-[var(--fill-muted)]',
  PARSING: 'bg-[var(--fill-muted)]',
  NEEDS_REVIEW: 'bg-[var(--fill-change)]',
  READY: 'bg-accent',
  APPLYING: 'bg-accent',
  APPLIED: 'bg-[var(--fill-add)]',
  FAILED: 'bg-[var(--fill-cancel)]',
};

/**
 * Стан завдання словом з крапкою (для тих, що в роботі, — спінер; читач екрана чує зміну через
 * aria-live списку). Без «табличок»: колір лише в крапці.
 */
export function JobStatusBadge({ status }: { status: ImportJobStatus }) {
  return (
    <span className="inline-flex items-center gap-2 text-ui font-[550] text-primary">
      {isBusy(status) ? <Spinner size="sm" label="у роботі" /> : <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', STATUS_DOT[status])} />}
      {IMPORT_STATUS_LABEL[status]}
    </span>
  );
}

/**
 * Що в звіті — одна найважливіша річ: що чекає рішення, інакше що нового, інакше скільки розпізнано.
 * Повний підсумок — у підказці та в самому завданні.
 */
export function JobSummary({ job }: { job: Pick<ImportJob, 'status' | 'summary' | 'error'> }) {
  if (job.status === 'FAILED') {
    return (
      // причину збою треба прочитати повністю: переносимо, а не обрізаємо
      <span className="text-caption text-pretty text-danger">
        {job.error ?? 'Файл не вдалося розібрати.'}
      </span>
    );
  }
  if (isBusy(job.status) && job.summary.recognized === 0) return <span className="text-caption text-muted">Звіт ще готується…</span>;
  const { recognized, unknown, conflicts } = job.summary;
  const full = summarySentence(job.summary);
  if (conflicts > 0) {
    return (
      <span className="text-caption font-[650] text-change" title={full}>
        {conflicts} {conflicts % 10 === 1 && conflicts % 100 !== 11 ? 'чекає' : 'чекають'} рішення
      </span>
    );
  }
  return (
    <span className="text-caption text-secondary" title={full}>
      {unknown > 0 ? countLabel(unknown, ['новий запис', 'нові записи', 'нових записів']) : countLabel(recognized, ['рядок', 'рядки', 'рядків'])}
    </span>
  );
}
