import { PencilLine } from 'lucide-react';
import { useState } from 'react';
import { type ImportJob, type OrderDraft, typedDraft, useImportJob } from '@/api/import';
import type { Uuid } from '@/api/types';
import {
  Button,
  LoadingRegion,
  PageErrorBanner,
  Skeleton,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/date';
import { ApplyPanel } from './ApplyPanel';
import { blockingReason, decisionRows, type ReviewTab } from './decisions';
import { CurriculumPreview } from './drafts/CurriculumPreview';
import { OrderDraftEditor } from './drafts/OrderDraftEditor';
import { OrderPreview } from './drafts/OrderPreview';
import { ProgramPreview } from './drafts/ProgramPreview';
import { TimetablePreview } from './drafts/TimetablePreview';
import { IMPORT_KIND_LABEL, summarySentence, usdShort } from './labels';
import { JobStatusBadge } from './JobStatus';
import { LoadOptionsPanel } from './LoadOptionsPanel';
import { ParsingProgress } from './ParsingProgress';
import { MappingPanel } from './MappingPanel';
import { ReportTable } from './ReportTable';
import { FileIcon } from './UploadPanel';

type Tab = ReviewTab;

/**
 * Чернетка розпорядження: перегляд у розкладці документа і звірка рядків. Прочитане ШІ треба звірити й
 * зберегти, інакше сервер не дасть застосувати (NEEDS_REVIEW).
 */
function OrderReview({ job, draft }: { job: ImportJob; draft: OrderDraft }) {
  const [editing, setEditing] = useState(false);
  // FAILED з чернеткою — core відхилив застосування: розпорядження виправляють і застосовують ще раз
  const editable = (job.status === 'NEEDS_REVIEW' || job.status === 'READY' || job.status === 'FAILED') && job.version !== undefined;
  const mustReview = draft.source === 'AI' && !draft.reviewed;
  if (editing && editable && job.version !== undefined) {
    return <OrderDraftEditor jobId={job.id} version={job.version} draft={draft} onDone={() => setEditing(false)} />;
  }
  return (
    <div className="flex flex-col gap-3">
      {editable ? (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className={cn('max-w-prose text-ui', mustReview ? 'text-primary' : 'text-secondary')}>
            {mustReview
              ? 'Рядки прочитав ШІ з фото. Звірте їх з оригіналом, виправте помилки й збережіть — лише тоді розпорядження можна застосувати.'
              : job.status === 'FAILED'
                ? 'Розпорядження не застосовано. Виправте номер, дату чи рядки за повідомленням вище й застосуйте ще раз.'
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
export function JobReview({ jobId }: { jobId: Uuid }) {
  const job = useImportJob(jobId);
  const [tab, setTab] = useState<Tab | null>(null);

  if (job.isPending) {
    return (
      <LoadingRegion label="Завантажуємо імпорт" className="flex flex-col gap-4">
        <Skeleton className="h-8 w-80" />
        <Skeleton className="h-5 w-2/3 max-w-xl" />
        <Skeleton className="h-72 w-full" />
      </LoadingRegion>
    );
  }
  if (job.isError) {
    return (
      <PageErrorBanner
        error={job.error}
        title="Завдання імпорту не завантажено"
        retrying={job.isFetching}
        onRetry={() => void job.refetch()}
      />
    );
  }

  const data = job.data;
  const draft = typedDraft(data);
  const decisions = decisionRows(data);
  const parsing = data.status === 'UPLOADED' || data.status === 'PARSING';
  // файл не розібрано: вкладки були б порожні, усе сказано в панелі з причиною
  const nothingParsed = data.status === 'FAILED' && !draft && data.report.length === 0;
  const active: Tab = tab ?? (data.summary.conflicts > 0 ? blockingReason(data).tab : 'draft');

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-start gap-3">
        <FileIcon name={data.fileName} className="mt-1 size-7" />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h2 className="text-title font-semibold break-words text-primary">{data.fileName}</h2>
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-secondary">
            <span>{IMPORT_KIND_LABEL[data.kind]}</span>
            <span className="figures">завантажено {formatInstant(data.createdAt)}</span>
            {data.ai ? (
              <span className="figures" title={`${data.ai.model}: ${data.ai.inputTokens} токенів на вході, ${data.ai.outputTokens} на виході`}>
                прочитав ШІ, {usdShort(data.ai.costUsd)}
              </span>
            ) : null}
          </p>
        </div>
        {/* на телефоні стан — окремим рядком під назвою, щоб назва файлу не ламалась на три рядки */}
        <span className="max-sm:order-last max-sm:basis-full max-sm:ps-10">
          <JobStatusBadge status={data.status} />
        </span>
      </header>

      {parsing ? (
        <ParsingProgress job={data} />
      ) : (
        <>
          {/* підсумок одним реченням замість чотирьох плиток з числами */}
          <p className="text-body text-secondary">{summarySentence(data.summary)}</p>

          <ApplyPanel job={data} onShowTab={setTab} activeTab={active} />

          {nothingParsed ? null : (
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
                <div className="flex flex-col gap-4">
                  <LoadOptionsPanel key={`${data.id}-${data.version ?? 0}`} job={data} />
                  <ProgramPreview draft={draft.draft} checks={data.report.filter((row) => row.type === 'HOURS')} />
                </div>
              ) : (
                <div className="flex flex-col gap-4">
                  <LoadOptionsPanel key={`${data.id}-${data.version ?? 0}`} job={data} />
                  <CurriculumPreview draft={draft.draft} />
                </div>
              )}
            </TabsContent>
          </Tabs>
          )}
        </>
      )}
    </div>
  );
}
