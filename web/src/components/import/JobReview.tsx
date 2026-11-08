import { ArrowLeft, PencilLine } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { type ImportJob, type OrderDraft, typedDraft, useImportJob } from '@/api/import';
import type { Uuid } from '@/api/types';
import {
  Button,
  ErrorState,
  LoadingRegion,
  Skeleton,
  Spinner,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  buttonClass,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/date';
import { ApplyPanel } from './ApplyPanel';
import { decisionRows } from './decisions';
import { CurriculumPreview } from './drafts/CurriculumPreview';
import { OrderDraftEditor } from './drafts/OrderDraftEditor';
import { OrderPreview } from './drafts/OrderPreview';
import { ProgramPreview } from './drafts/ProgramPreview';
import { TimetablePreview } from './drafts/TimetablePreview';
import { IMPORT_KIND_LABEL } from './labels';
import { JobStatusBadge } from './JobStatus';
import { MappingPanel } from './MappingPanel';
import { ReportTable } from './ReportTable';
import { FileIcon } from './UploadPanel';

type Tab = 'decisions' | 'report' | 'draft';

/**
 * Чернетка розпорядження: перегляд у розкладці документа і звірка рядків. Прочитане ШІ треба звірити й
 * зберегти, інакше сервер не дасть застосувати (NEEDS_REVIEW).
 */
function OrderReview({ job, draft }: { job: ImportJob; draft: OrderDraft }) {
  const [editing, setEditing] = useState(false);
  const editable = (job.status === 'NEEDS_REVIEW' || job.status === 'READY') && job.version !== undefined;
  const mustReview = draft.source === 'AI' && !draft.reviewed;
  if (editing && editable && job.version !== undefined) {
    return <OrderDraftEditor jobId={job.id} version={job.version} draft={draft} onDone={() => setEditing(false)} />;
  }
  return (
    <div className="flex flex-col gap-3">
      {editable ? (
        <div
          className={cn(
            'flex flex-wrap items-center justify-between gap-3 rounded-panel border px-4 py-3',
            mustReview ? 'border-change bg-change-subtle' : 'border-default bg-surface',
          )}
        >
          <p className={cn('text-ui', mustReview ? 'text-change' : 'text-secondary')}>
            {mustReview
              ? 'Рядки прочитав ШІ з фото. Звірте їх з оригіналом, виправте помилки й збережіть — лише тоді розпорядження можна застосувати.'
              : draft.reviewed
                ? 'Рядки звірено вручну. За потреби їх можна виправити ще раз.'
                : 'Рядки можна виправити перед застосуванням.'}
          </p>
          <Button size="sm" variant={mustReview ? 'primary' : 'secondary'} onClick={() => setEditing(true)} iconStart={<PencilLine aria-hidden="true" className="size-4" />}>
            {mustReview ? 'Звірити рядки' : 'Виправити рядки'}
          </Button>
        </div>
      ) : null}
      <OrderPreview draft={draft} />
    </div>
  );
}

/** Перегляд завдання імпорту: підсумок, рішення щодо назв, звіт, чернетка й застосування. */
export function JobReview({ jobId, backTo }: { jobId: Uuid; backTo: string }) {
  const job = useImportJob(jobId);
  const [tab, setTab] = useState<Tab | null>(null);

  if (job.isPending) {
    return (
      <LoadingRegion label="Завантажуємо імпорт" className="flex flex-col gap-4">
        <Skeleton className="h-8 w-80" />
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-20" />
          ))}
        </div>
        <Skeleton className="h-72 w-full" />
      </LoadingRegion>
    );
  }
  if (job.isError) {
    return (
      <ErrorState
        error={job.error}
        onRetry={() => void job.refetch()}
        action={
          <Link to={backTo} className={buttonClass({ variant: 'ghost' })}>
            До всіх імпортів
          </Link>
        }
      />
    );
  }

  const data = job.data;
  const draft = typedDraft(data);
  const decisions = decisionRows(data);
  const parsing = data.status === 'UPLOADED' || data.status === 'PARSING';
  const active: Tab = tab ?? (data.summary.conflicts > 0 ? 'decisions' : 'draft');
  const stats = [
    { label: 'Розпізнано', value: data.summary.recognized, tone: 'text-primary' },
    { label: 'Нових записів', value: data.summary.unknown, tone: 'text-primary' },
    { label: 'Чекають рішення', value: data.summary.conflicts, tone: data.summary.conflicts > 0 ? 'text-change' : 'text-primary' },
    { label: 'Попереджень', value: data.summary.warnings, tone: 'text-primary' },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Link
        to={backTo}
        className="inline-flex items-center gap-1.5 self-start rounded-plate text-ui font-semibold text-accent underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        Усі імпорти
      </Link>

      <header className="flex flex-wrap items-start gap-3">
        <FileIcon name={data.fileName} className="mt-1 size-7" />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h2 className="text-title font-semibold break-words text-primary">{data.fileName}</h2>
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-secondary">
            <span>{IMPORT_KIND_LABEL[data.kind]}</span>
            <span className="figures">завантажено {formatInstant(data.createdAt)}</span>
            {data.ai ? (
              <span className="figures" title={`${data.ai.inputTokens} токенів на вході, ${data.ai.outputTokens} на виході`}>
                ШІ: {data.ai.model} · ${data.ai.costUsd.toFixed(3)}
              </span>
            ) : (
              <span>без ШІ</span>
            )}
          </p>
        </div>
        <JobStatusBadge status={data.status} />
      </header>

      {parsing ? (
        <div aria-live="polite" className="flex items-center gap-3 rounded-panel border border-default bg-surface px-4 py-6">
          <Spinner size="lg" label="Розбираємо файл" />
          <div className="flex flex-col gap-0.5">
            <p className="text-ui font-semibold text-primary">Розбираємо файл…</p>
            <p className="text-caption text-secondary">
              Читаємо таблиці, зіставляємо назви з каталогом. Сторінка оновиться сама — можна піти й повернутися.
            </p>
          </div>
        </div>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {stats.map((stat) => (
              <div key={stat.label} className="flex flex-col-reverse rounded-panel border border-default bg-surface px-4 py-3">
                <dt className="mt-1 text-caption text-muted">{stat.label}</dt>
                <dd className={cn('font-condensed text-display leading-none font-bold figures', stat.tone)}>{stat.value}</dd>
              </div>
            ))}
          </dl>

          <ApplyPanel job={data} onShowDecisions={() => setTab('decisions')} />

          <Tabs value={active} onValueChange={(value) => setTab(value as Tab)}>
            <TabsList aria-label="Розділи імпорту">
              <TabsTrigger value="decisions" count={decisions.length}>
                Рішення
              </TabsTrigger>
              <TabsTrigger value="report" count={data.report.length}>
                Звіт
              </TabsTrigger>
              <TabsTrigger value="draft">Чернетка</TabsTrigger>
            </TabsList>
            <TabsContent value="decisions">
              <MappingPanel key={`${data.id}-${data.status}`} job={data} />
            </TabsContent>
            <TabsContent value="report">
              <ReportTable report={data.report} />
            </TabsContent>
            <TabsContent value="draft">
              {!draft ? (
                <p className="rounded-panel border border-default bg-surface px-4 py-6 text-ui text-secondary">
                  {data.status === 'FAILED' ? 'Чернетки немає: файл не вдалося розібрати.' : 'Чернетка ще не готова.'}
                </p>
              ) : draft.kind === 'TIMETABLE_XLSX' ? (
                <TimetablePreview draft={draft.draft} />
              ) : draft.kind === 'ORDER_DOCUMENT' ? (
                <OrderReview job={data} draft={draft.draft} />
              ) : draft.kind === 'DISCIPLINE_PROGRAM' ? (
                <ProgramPreview draft={draft.draft} checks={data.report.filter((row) => row.type === 'HOURS')} />
              ) : (
                <CurriculumPreview draft={draft.draft} />
              )}
            </TabsContent>
          </Tabs>
        </>
      )}
    </div>
  );
}
