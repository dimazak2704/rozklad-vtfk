import { useMemo, useState } from 'react';
import { IMPORT_JOBS_PAGE_SIZE, useImportJobs, type ImportJob } from '@/api/import';
import { DataTable, EmptyState, type DataTableColumn } from '@/components/ui';
import { formatInstant, formatInstantTime, todayInKyiv } from '@/lib/date';
import { IMPORT_KIND_LABEL, usdShort } from './labels';
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
  // сторінки рахує importer: старі завдання не тягнемо всі одразу
  const [page, setPage] = useState(0);
  const jobs = useImportJobs({}, page);
  const data = jobs.data;

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
      {
        id: 'status',
        header: 'Стан',
        width: '15rem',
        cell: (job) => (
          <span className="flex flex-col gap-0.5">
            <JobStatusBadge status={job.status} />
            <JobSummary job={job} />
          </span>
        ),
      },
      {
        id: 'ai',
        header: 'ШІ',
        numeric: true,
        width: '5.5rem',
        hideBelow: 'md',
        className: 'max-md:hidden!',
        cell: (job) =>
          job.ai ? (
            <span title={`${job.ai.model}: ${job.ai.inputTokens} + ${job.ai.outputTokens} токенів`}>{usdShort(job.ai.costUsd)}</span>
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
        className: 'figures whitespace-nowrap text-secondary max-md:hidden!',
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
        rows={data?.items}
        getRowId={(job) => job.id}
        loading={jobs.isPending}
        error={jobs.error}
        onRetry={() => void jobs.refetch()}
        onRowClick={onOpen}
        // на телефоні назва файлу — головне: вона займає всю ширину й переноситься на 2 рядки, стан — рядком
        // під нею, дата — підписом; праворуч стан лишається лише від 640 px, де назві вистачає місця
        mobileRow={(job) => ({
          title: (
            <span className="flex flex-col gap-0.5">
              <span className="line-clamp-2 [overflow-wrap:anywhere]" title={job.fileName}>
                {job.fileName}
              </span>
              <span className="text-caption font-normal sm:hidden">
                <JobStatusBadge status={job.status} />
              </span>
            </span>
          ),
          caption: `${IMPORT_KIND_LABEL[job.kind]} · ${when(job.createdAt)}`,
          status: (
            <span className="max-sm:hidden">
              <JobStatusBadge status={job.status} />
            </span>
          ),
        })}
        pagination={
          data && data.total > IMPORT_JOBS_PAGE_SIZE
            ? { page, size: IMPORT_JOBS_PAGE_SIZE, total: data.total, onPageChange: setPage, noun: 'завдань' }
            : undefined
        }
        empty={
          <EmptyState
            size="sm"
            title="Імпортів ще не було."
            description="Почніть з таблиці розкладу: з неї система візьме групи, викладачів, дисципліни й аудиторії."
          />
        }
      />
    </div>
  );
}
