import { CircleCheck, CircleStop, CircleX, FileDiff, FolderOpen, Hourglass, LoaderCircle } from 'lucide-react';
import { Link } from 'react-router';
import { isJobActive, parseScore, type GenerationJobView } from '@/api/generation';
import type { Uuid } from '@/api/types';
import { Button, buttonClass, Panel, StatusDot } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { comparisonRows, elapsedSeconds, formatSeconds } from './model';
import { ConstraintAnalysis, MetricsComparison } from './ResultSummary';
import { ScoreCharts } from './ScoreCharts';

interface JobPanelProps {
  job: GenerationJobView;
  /** Опублікована версія — для «Порівняти з поточним». */
  publishedId: Uuid | null;
  cancelling: boolean;
  onCancel: () => void;
  onRestart: () => void;
}

const STATUS_TITLE: Record<GenerationJobView['status'], string> = {
  QUEUED: 'У черзі',
  RUNNING: 'Розвʼязується',
  SOLVED: 'Готово',
  FAILED: 'Не вдалося',
  CANCELLED: 'Скасовано',
};

/** Поточна (або вибрана з історії) задача: стан, хід розвʼязання наживо, результат і що з ним робити. */
export function JobPanel({ job, publishedId, cancelling, onCancel, onRestart }: JobPanelProps) {
  const active = isJobActive(job.status);
  const limit = job.params?.timeLimitSeconds ?? null;
  const elapsed = elapsedSeconds(job);
  const score = parseScore(job.bestScore);
  const rows = comparisonRows(job);

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
      description={job.startedAt ? `Запущено ${formatInstant(job.startedAt)}` : 'Готуємо задачу для розвʼязувача…'}
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
        ) : (
          <Button size="sm" onClick={onRestart}>
            Запустити ще раз
          </Button>
        )
      }
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
          <Stat label="Час" value={formatSeconds(elapsed)} sub={limit ? `з ${formatSeconds(limit)} ліміту` : undefined} />
          <Stat label="Жорсткі порушення" value={score ? String(Math.abs(score.hard)) : '—'} tone={score && score.hard < 0 ? 'danger' : score ? 'add' : undefined} />
          <Stat label="Мʼякий штраф" value={score ? Math.abs(score.soft).toLocaleString('uk-UA') : '—'} />
          {active && limit ? (
            <div className="flex min-w-48 flex-1 flex-col gap-1.5" aria-hidden="true">
              <div className="h-1.5 overflow-hidden rounded-full bg-sunken">
                <div
                  className="h-full rounded-full bg-accent transition-[width] duration-700 ease-linear motion-reduce:transition-none"
                  style={{ width: `${Math.min(100, (elapsed / limit) * 100)}%` }}
                />
              </div>
              <span className="text-meta text-muted">Розвʼязувач зупиниться не пізніше ніж за {formatSeconds(Math.max(0, limit - elapsed))}</span>
            </div>
          ) : null}
        </div>

        {job.status === 'QUEUED' ? (
          <p className="flex items-center gap-2 text-ui text-secondary">
            <LoaderCircle aria-hidden="true" className="size-4 text-accent motion-safe:animate-spin" />
            Збираємо групи, викладачів, аудиторії й закріплені пари — за мить почнеться розвʼязання.
          </p>
        ) : null}

        {job.progress.length > 0 ? <ScoreCharts progress={job.progress} limitSeconds={limit} live={active} /> : null}

        {job.status === 'FAILED' ? (
          <p role="alert" className="rounded-control border border-danger bg-danger-subtle px-3 py-2.5 text-ui text-primary">
            {job.error ?? 'Розвʼязувач завершився з помилкою без пояснення.'} Чернетку не створено — змініть параметри й спробуйте ще раз.
          </p>
        ) : null}

        {job.status === 'CANCELLED' ? (
          <p className="text-ui text-secondary">Генерацію зупинено вручну. Чернетку не створено; найкраща знайдена оцінка — вище.</p>
        ) : null}

        {job.status === 'SOLVED' ? (
          <div className="flex flex-col gap-5 border-t border-separator pt-5">
            <div className="flex flex-wrap items-center gap-2">
              {job.resultVersionId ? (
                <>
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
                </>
              ) : null}
              <span className="text-caption text-muted">Результат — нова чернетка: перегляньте, підправте й опублікуйте.</span>
            </div>
            <div className="grid gap-6 lg:grid-cols-2">
              {rows.length > 0 ? <MetricsComparison rows={rows} /> : null}
              {job.analysis ? <ConstraintAnalysis items={job.analysis} /> : null}
            </div>
          </div>
        ) : null}
      </div>
    </Panel>
  );
}

function Stat({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'danger' | 'add' }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-caption text-muted">{label}</span>
      <span
        className={
          tone === 'danger'
            ? 'font-condensed text-headline leading-none font-bold text-danger figures'
            : tone === 'add'
              ? 'font-condensed text-headline leading-none font-bold text-add figures'
              : 'font-condensed text-headline leading-none font-bold text-primary figures'
        }
      >
        {value}
      </span>
      {sub ? <span className="text-meta text-muted">{sub}</span> : null}
    </div>
  );
}
