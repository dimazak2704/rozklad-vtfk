import { CircleCheck, CircleStop, CircleX, FileDiff, FolderOpen, Hourglass, LoaderCircle } from 'lucide-react';
import { Link } from 'react-router';
import { isJobActive, parseScore } from '@/api/generation';
import type { GenerationJobDto, MetricsDto, Uuid } from '@/api/types';
import { Button, buttonClass, Panel, StatusDot } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { Disclosure } from './Disclosure';
import { comparisonRows, elapsedSeconds, formatSeconds } from './model';
import { ConstraintAnalysis, MetricsComparison } from './ResultSummary';
import { ScoreCharts } from './ScoreCharts';

interface JobPanelProps {
  job: GenerationJobDto;
  /** Опублікована версія — для «Порівняти з поточним». */
  publishedId: Uuid | null;
  /** Метрики базової версії — «Було», якщо задача їх не принесла. */
  baseMetrics?: MetricsDto | null;
  cancelling: boolean;
  onCancel: () => void;
}

const STATUS_TITLE: Record<GenerationJobDto['status'], string> = {
  QUEUED: 'У черзі',
  RUNNING: 'Розвʼязується',
  SOLVED: 'Готово',
  FAILED: 'Не вдалося',
  CANCELLED: 'Скасовано',
};

const HARD_FORMS = ['жорстке порушення', 'жорсткі порушення', 'жорстких порушень'] as const;

/** Підсумок одним реченням замість трьох плиток: скільки тривало і що вийшло. */
function resultSentence(job: GenerationJobDto, elapsed: number): string {
  const score = parseScore(job.bestScore);
  const time = formatSeconds(elapsed);
  if (job.status === 'RUNNING') {
    return job.timeLimitSeconds ? `Минуло ${time} з ${formatSeconds(job.timeLimitSeconds)}.` : `Минуло ${time}.`;
  }
  if (job.status === 'QUEUED') return 'Збираємо групи, викладачів, аудиторії й закріплені пари.';
  if (!score) return `Тривало ${time}.`;
  const hard = Math.abs(score.hard);
  const soft = Math.abs(score.soft).toLocaleString('uk-UA');
  const hardText = hard === 0 ? 'жорстких порушень немає' : countLabel(hard, HARD_FORMS);
  return `Тривало ${time}: ${hardText}, мʼякий штраф ${soft}.`;
}

/** Поточна (або вибрана з історії) задача: стан, хід розвʼязання наживо, результат і що з ним робити. */
export function JobPanel({ job, publishedId, baseMetrics, cancelling, onCancel }: JobPanelProps) {
  const active = isJobActive(job.status);
  const limit = job.timeLimitSeconds;
  const elapsed = elapsedSeconds(job);
  const rows = comparisonRows(job, baseMetrics);
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
          {job.status === 'SOLVED' ? <CircleCheck aria-hidden="true" className="size-4 text-add" /> : null}
          {job.status === 'FAILED' ? <CircleX aria-hidden="true" className="size-4 text-danger" /> : null}
          {job.status === 'CANCELLED' ? <CircleStop aria-hidden="true" className="size-4 text-muted" /> : null}
          {STATUS_TITLE[job.status]}
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
          {active && limit ? (
            <div aria-hidden="true" className="h-1.5 max-w-md overflow-hidden rounded-full bg-[var(--fill-track-future)]">
              <div
                className="h-full rounded-full bg-accent transition-[width] duration-700 ease-linear motion-reduce:transition-none"
                style={{ width: `${Math.min(100, (elapsed / limit) * 100)}%` }}
              />
            </div>
          ) : null}
        </div>

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
            {job.analysis ? <ConstraintAnalysis items={job.analysis} /> : null}
          </div>
        ) : null}

        {charts && !active ? (
          <Disclosure summary="Хід розвʼязання" className="border-t border-separator pt-3">
            {charts}
          </Disclosure>
        ) : null}
      </div>
    </Panel>
  );
}
