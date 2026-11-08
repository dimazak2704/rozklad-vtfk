import { useMemo } from 'react';
import { useImportJobs, type ImportJob } from '@/api/import';
import { DataTable, EmptyState, type DataTableColumn } from '@/components/ui';
import { formatInstant, formatInstantTime, todayInKyiv } from '@/lib/date';
import { IMPORT_KIND_LABEL } from './labels';
import { JobStatusBadge, JobSummary } from './JobStatus';
import { FileIcon } from './UploadPanel';

interface JobListProps {
  onOpen: (job: ImportJob) => void;
}

/** «сьогодні, 08:41» або «06.11.2026, 17:20». */
function when(instant: string): string {
  return todayInKyiv(new Date(instant)) === todayInKyiv() ? `сьогодні, ${formatInstantTime(instant)}` : formatInstant(instant);
}

/** Завдання імпорту: файл, вид, стан (оновлюється сам, поки сервер працює), що в звіті, витрати ШІ. */
export function JobList({ onOpen }: JobListProps) {
  const jobs = useImportJobs();

  const columns = useMemo<Array<DataTableColumn<ImportJob>>>(
    () => [
      {
        id: 'file',
        header: 'Файл',
        rowHeader: true,
        cell: (job) => (
          <span className="flex min-w-0 items-center gap-2.5">
            <FileIcon name={job.fileName} />
            <span className="flex min-w-0 flex-col">
              <span className="truncate" title={job.fileName}>{job.fileName}</span>
              <span className="text-caption font-normal text-muted">{IMPORT_KIND_LABEL[job.kind]}</span>
            </span>
          </span>
        ),
      },
      { id: 'status', header: 'Стан', width: '13rem', cell: (job) => <JobStatusBadge status={job.status} /> },
      { id: 'summary', header: 'Звіт', hideBelow: 'md', cell: (job) => <JobSummary job={job} /> },
      {
        id: 'ai',
        header: 'ШІ',
        numeric: true,
        width: '5.5rem',
        hideBelow: 'sm',
        cell: (job) =>
          job.ai ? (
            <span title={`${job.ai.model}: ${job.ai.inputTokens} + ${job.ai.outputTokens} токенів`}>${job.ai.costUsd.toFixed(3)}</span>
          ) : (
            <span className="text-muted" title="Без моделі ШІ: розібрано детерміновано">
              —
            </span>
          ),
      },
      {
        id: 'created',
        header: 'Коли',
        width: '10.5rem',
        hideBelow: 'lg',
        className: 'figures whitespace-nowrap text-secondary',
        cell: (job) => when(job.createdAt),
      },
    ],
    [],
  );

  return (
    <div aria-live="polite" aria-relevant="text">
      <DataTable
        caption="Завдання імпорту"
        columns={columns}
        rows={jobs.data}
        getRowId={(job) => job.id}
        loading={jobs.isPending}
        error={jobs.error}
        onRetry={() => void jobs.refetch()}
        onRowClick={onOpen}
        empty={
          <EmptyState
            size="sm"
            title="Імпортів ще не було"
            description="Почніть з таблиці розкладу: з неї система візьме групи, викладачів, дисципліни й аудиторії."
          />
        }
      />
    </div>
  );
}
