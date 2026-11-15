import { CircleCheck, CircleStop, CircleX, FileDiff, FolderOpen, Hourglass, LoaderCircle, RotateCcw, TriangleAlert } from 'lucide-react';
import { Link } from 'react-router';
import { isJobActive, jobHardScore, normalizeWeights, parseScore, solvedWithConflicts } from '@/api/generation';
import type { GenerationJobDto, Uuid } from '@/api/types';
import { Button, buttonClass, Panel, StatusDot } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { Disclosure } from './Disclosure';
import {
  comparisonRows,
  elapsedSeconds,
  formatSeconds,
  groupComparison,
  jobHasParams,
  jobPrioritiesText,
  jobScopeText,
  jobStatusTitle,
  outcomeSentence,
  type BaseVersionInfo,
} from './model';
import { changedPriorities } from './priorities';
import { ConstraintAnalysis, GroupComparison, MetricsComparison } from './ResultSummary';
import { ScoreCharts } from './ScoreCharts';

interface JobPanelProps {
  job: GenerationJobDto;
  /** Опублікована версія — для «Порівняти з поточним». */
  publishedId: Uuid | null;
  /** Базова версія задачі (зі списку версій): «Було», якщо задача його не принесла, і її назва. null — версії вже немає. */
  base?: (BaseVersionInfo & { name: string }) | null;
  cancelling: boolean;
  onCancel: () => void;
  /** «Взяти ці параметри»: перенести параметри задачі у форму. */
  onUseParams?: () => void;
}

const HARD_FORMS = ['жорстке порушення', 'жорсткі порушення', 'жорстких порушень'] as const;

/** Підсумок одним реченням замість трьох плиток: скільки тривало і що вийшло. */
function resultSentence(job: GenerationJobDto, elapsed: number): string {
  const score = parseScore(job.bestScore);
  const time = formatSeconds(elapsed);
  if (job.status === 'RUNNING') {
    return job.timeLimitSeconds ? `Минуло ${time} з ${formatSeconds(job.timeLimitSeconds)}.` : `Минуло ${time}.`;
  }
  if (job.status === 'QUEUED') return 'Збираємо групи, викладачів, аудиторії й закріплені пари.';
  const hardScore = jobHardScore(job);
  if (!score || hardScore === null) return `Тривало ${time}.`;
  const hard = Math.abs(hardScore);
  const soft = Math.abs(score.soft).toLocaleString('uk-UA');
  const hardText = hard === 0 ? 'жорстких порушень немає' : countLabel(hard, HARD_FORMS);
  return `Тривало ${time}: ${hardText}, мʼякий штраф ${soft}.`;
}

/** Поточна (або вибрана з історії) задача: стан, хід розвʼязання наживо, результат і що з ним робити. */
export function JobPanel({ job, publishedId, base, cancelling, onCancel, onUseParams }: JobPanelProps) {
  const active = isJobActive(job.status);
  const limit = job.timeLimitSeconds;
  const elapsed = elapsedSeconds(job);
  const rows = comparisonRows(job, base);
  const groups = groupComparison(job, base);
  const outcome = job.status === 'SOLVED' ? outcomeSentence(job.outcome) : null;
  const withConflicts = solvedWithConflicts(job);
  const scoped = Boolean(job.scope && (job.scope.groupIds.length > 0 || job.scope.courses.length > 0));
  const charts =
    job.progress.length > 0 ? (
      <ScoreCharts progress={job.progress} limitSeconds={limit} live={active} endSeconds={active ? undefined : elapsed} />
    ) : null;

  return (
    <Panel
      title={
        <span className="flex items-center gap-2" aria-live="polite">
          {job.status === 'QUEUED' ? <Hourglass aria-hidden="true" className="size-4 text-muted" /> : null}
          {job.status === 'RUNNING' ? <StatusDot tone="live" pulse label="Наживо" /> : null}
          {job.status === 'SOLVED' && !withConflicts ? <CircleCheck aria-hidden="true" className="size-4 text-add" /> : null}
          {withConflicts ? <TriangleAlert aria-hidden="true" className="size-4 text-change" /> : null}
          {job.status === 'FAILED' ? <CircleX aria-hidden="true" className="size-4 text-danger" /> : null}
          {job.status === 'CANCELLED' ? <CircleStop aria-hidden="true" className="size-4 text-muted" /> : null}
          {jobStatusTitle(job)}
        </span>
      }
      description={job.startedAt ? `Запущено ${formatInstant(job.startedAt)}` : undefined}
      actions={
        active ? (
          <Button
            variant="danger-ghost"
            size="sm"
            onClick={onCancel}
            loading={cancelling}
            iconStart={<CircleStop aria-hidden="true" className="size-4" />}
          >
            Скасувати
          </Button>
        ) : null
      }
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-2">
          <p className="flex items-center gap-2 text-body text-primary">
            {job.status === 'QUEUED' ? <LoaderCircle aria-hidden="true" className="size-4 shrink-0 text-accent motion-safe:animate-spin" /> : null}
            <span>{resultSentence(job, elapsed)}</span>
          </p>
          {withConflicts ? (
            <p className="text-ui text-change">
              Розвʼязувач не прибрав усі жорсткі конфлікти. Чернетку збережено — перегляньте конфлікти в редакторі перед публікацією.
            </p>
          ) : null}
          {outcome ? <p className="text-ui text-secondary">{outcome}</p> : null}
          {active && limit ? (
            <div aria-hidden="true" className="h-1.5 max-w-md overflow-hidden rounded-full bg-[var(--fill-track-future)]">
              <div
                className="h-full rounded-full bg-accent transition-[width] duration-700 ease-linear motion-reduce:transition-none"
                style={{ width: `${Math.min(100, (elapsed / limit) * 100)}%` }}
              />
            </div>
          ) : null}
        </div>

        <JobParams job={job} baseName={base?.name ?? null} onUseParams={active ? undefined : onUseParams} />

        {/* наживо графік — головне; після кінця — подробиця на вимогу */}
        {charts && active ? charts : null}

        {job.status === 'FAILED' ? (
          <p role="alert" className="text-ui text-primary">
            {job.error ?? 'Розвʼязувач завершився з помилкою без пояснення.'} Чернетку не створено — змініть параметри й запустіть ще раз.
          </p>
        ) : null}

        {job.status === 'CANCELLED' ? <p className="text-ui text-secondary">Генерацію зупинено вручну, чернетку не створено.</p> : null}

        {job.status === 'SOLVED' && job.resultVersionId ? (
          <div className="flex flex-wrap items-center gap-2">
            <Link to={`/admin/timetable?version=${encodeURIComponent(job.resultVersionId)}`} className={buttonClass({ variant: 'primary' })}>
              <FolderOpen aria-hidden="true" className="size-4" />
              Відкрити чернетку
            </Link>
            {publishedId ? (
              <Link
                to={`/admin/timetable?version=${encodeURIComponent(job.resultVersionId)}&mode=diff&base=${encodeURIComponent(publishedId)}`}
                className={buttonClass()}
              >
                <FileDiff aria-hidden="true" className="size-4" />
                Порівняти з поточним
              </Link>
            ) : null}
          </div>
        ) : null}

        {job.status === 'SOLVED' && (rows.length > 0 || job.analysis) ? (
          <div className="grid gap-6 border-t border-separator pt-5 xl:grid-cols-2">
            {rows.length > 0 ? <MetricsComparison rows={rows} /> : null}
            {job.analysis ? <ConstraintAnalysis items={job.analysis} scoped={scoped} /> : null}
          </div>
        ) : null}

        {job.status === 'SOLVED' && groups.length > 0 ? <GroupComparison rows={groups} /> : null}

        {charts && !active ? (
          <Disclosure summary="Хід розвʼязання" className="border-t border-separator pt-3">
            {charts}
          </Disclosure>
        ) : null}
      </div>
    </Panel>
  );
}

/**
 * Власні параметри задачі (genfix B8): які групи, від якої версії, скільки часу і які пріоритети змінено.
 * Стара задача їх не має — тоді так і пишемо, а не підставляємо поточну форму.
 */
function JobParams({ job, baseName, onUseParams }: { job: GenerationJobDto; baseName: string | null; onUseParams?: () => void }) {
  if (!jobHasParams(job)) {
    return <p className="text-caption text-muted">Параметри цього запуску невідомі: сервер їх не зберіг.</p>;
  }
  const weights = normalizeWeights(job.weights);
  const changed = weights ? changedPriorities(weights) : [];
  const base = job.baseVersionId ? (baseName ? `«${baseName}»` : 'версія, якої вже немає') : 'без базової — з навантаження';
  return (
    <div className="flex flex-col gap-2 rounded-control bg-sunken px-3 py-2.5">
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-caption">
        <dt className="text-secondary">Групи</dt>
        <dd className="text-primary">{jobScopeText(job)}</dd>
        <dt className="text-secondary">Базова версія</dt>
        <dd className="text-primary">{base}</dd>
        {job.timeLimitSeconds ? (
          <>
            <dt className="text-secondary">Ліміт часу</dt>
            <dd className="text-primary">{formatSeconds(job.timeLimitSeconds)}</dd>
          </>
        ) : null}
        <dt className="text-secondary">Пріоритети</dt>
        <dd className="text-primary">
          {jobPrioritiesText(job, changed)}
        </dd>
      </dl>
      {onUseParams ? (
        <Button size="sm" variant="ghost" className="self-start" onClick={onUseParams} iconStart={<RotateCcw aria-hidden="true" className="size-3.5" />}>
          Взяти ці параметри
        </Button>
      ) : null}
    </div>
  );
}
