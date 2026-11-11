import { Cpu, Play } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { isApiError } from '@/api/errors';
import {
  isJobActive,
  useCancelGeneration,
  useGenerationJob,
  useGenerationJobs,
  useGenerationLiveUpdates,
  useStartGeneration,
} from '@/api/generation';
import { useServerEvent } from '@/api/live';
import { usePublicMeta } from '@/api/meta';
import { usePlanningCatalog, useTimetableVersions } from '@/api/timetable';
import type { Uuid } from '@/api/types';
import { BaselinePanel } from '@/components/generation/BaselinePanel';
import { GenerationForm } from '@/components/generation/GenerationForm';
import { JobHistory } from '@/components/generation/JobHistory';
import { JobPanel } from '@/components/generation/JobPanel';
import { initialForm, toJobRequest, validateForm, type GenerationFormState } from '@/components/generation/model';
import { publishedVersion } from '@/components/timetable/model/versions';
import { Button, EmptyState, LoadingRegion, notify, PageErrorBanner, PageHeader, Skeleton } from '@/components/ui';
import { useRegisterCommands } from '@/stores/commands';

/**
 * Генератор розкладу: параметри (групи, базова версія, ліміт часу, ваги), запуск, хід розвʼязання
 * наживо (SSE + опитування), скасування, результат «Було / Стало» і нова чернетка.
 */
export default function GenerationPage() {
  const meta = usePublicMeta();
  const semesterId = meta.data?.semester?.id;
  const versions = useTimetableVersions(semesterId);
  const catalog = usePlanningCatalog();
  const jobs = useGenerationJobs(semesterId);
  const [params, setParams] = useSearchParams();
  const selectedJobId = params.get('job') ?? jobs.data?.[0]?.id ?? null;
  const job = useGenerationJob(selectedJobId);
  const start = useStartGeneration();
  const cancel = useCancelGeneration();
  const [draft, setDraft] = useState<GenerationFormState | null>(null);
  const [submitted, setSubmitted] = useState(false);
  useGenerationLiveUpdates(selectedJobId);

  const published = publishedVersion(versions.data ?? []);
  const form = draft ?? initialForm(published?.id ?? null);
  const baseVersion = (versions.data ?? []).find((v) => v.id === form.baseVersionId) ?? published;
  const busy = (jobs.data ?? []).some((j) => isJobActive(j.status)) || isJobActive(job.data?.status);

  useServerEvent('generation.completed', (event) => {
    if (event.status === 'SOLVED') {
      notify.success('Генерацію завершено', {
        description: 'Результат збережено як нову чернетку — відкрийте й перегляньте її.',
      });
    }
  });

  const selectJob = (jobId: Uuid) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.set('job', jobId);
        return next;
      },
      { replace: true },
    );

  const launch = async () => {
    if (!semesterId) return;
    // помилки полів показуємо лише після спроби запуску; невалідну форму не відправляємо
    setSubmitted(true);
    if (Object.keys(validateForm(form)).length > 0) return;
    try {
      const created = await start.mutateAsync(toJobRequest(form, semesterId));
      selectJob(created.jobId);
      notify.info('Генерацію запущено', {
        description: 'Хід розвʼязання видно на графіку; сторінку можна закрити — задача працює на сервері.',
      });
    } catch (error) {
      const fields = isApiError(error) ? error.errors.map((e) => e.message).join('; ') : '';
      notify.error(error, 'Генерацію не запущено', fields ? { description: fields } : undefined);
    }
  };

  const stop = (jobId: Uuid) =>
    cancel.mutate(jobId, {
      onSuccess: () =>
        notify.info('Генерацію скасовано', {
          description: 'Чернетку не створено.',
        }),
      onError: (error) => notify.error(error, 'Не вдалося скасувати генерацію'),
    });

  useRegisterCommands([
    {
      id: 'start-generation',
      label: 'Запустити генерацію розкладу',
      group: 'Генератор',
      icon: Cpu,
      perform: () => void launch(),
    },
  ]);

  const formReady = !(meta.isPending || versions.isPending || catalog.isPending);
  // головна дія — у заголовку, а не під довгою формою: її видно одразу на будь-якому екрані
  const header = (
    <PageHeader
      title="Генератор розкладу"
      primaryAction={
        <Button
          variant="primary"
          onClick={() => void launch()}
          disabled={busy || !formReady || !semesterId}
          loading={start.isPending}
          iconStart={<Play aria-hidden="true" className="size-4" />}
        >
          Запустити генерацію
        </Button>
      }
    />
  );

  const loadError = meta.error ?? versions.error ?? catalog.error;
  if (loadError) {
    return (
      <div className="flex flex-col gap-6">
        {header}
        <PageErrorBanner
          error={loadError}
          title="Дані для генератора не завантажено"
          retrying={meta.isFetching || versions.isFetching}
          onRetry={() => {
            if (meta.error) void meta.refetch();
            if (versions.error) void versions.refetch();
            catalog.refetch();
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {header}
      <div className="grid items-start gap-6 lg:grid-cols-[22rem_minmax(0,1fr)] 2xl:grid-cols-[26rem_minmax(0,1fr)]">
        {!formReady ? (
          <LoadingRegion label="Завантажуємо параметри…" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
            {Array.from({ length: 7 }, (_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </LoadingRegion>
        ) : (
          <GenerationForm
            form={form}
            onChange={setDraft}
            groups={catalog.data?.groups ?? []}
            versions={versions.data ?? []}
            busy={busy}
            submitted={submitted}
          />
        )}

        <div className="flex min-w-0 flex-col gap-6">
          {jobs.error ? (
            <PageErrorBanner error={jobs.error} title="Запуски не завантажено" retrying={jobs.isFetching} onRetry={() => void jobs.refetch()} />
          ) : jobs.isPending || (selectedJobId && job.isPending) ? (
            <LoadingRegion label="Завантажуємо запуски…" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
              <Skeleton className="h-6 w-40" />
              <Skeleton className="h-48 w-full" />
              <Skeleton className="h-24 w-full" />
            </LoadingRegion>
          ) : job.error ? (
            <PageErrorBanner error={job.error} title="Задачу не завантажено" retrying={job.isFetching} onRetry={() => void job.refetch()} />
          ) : job.data ? (
            <JobPanel
              job={job.data}
              publishedId={published?.id ?? null}
              baseMetrics={(versions.data ?? []).find((v) => v.id === job.data?.baseVersionId)?.metrics ?? published?.metrics ?? null}
              cancelling={cancel.isPending}
              onCancel={() => job.data && stop(job.data.id)}
            />
          ) : (
            <div className="flex flex-col gap-6 lg:pt-2">
              {baseVersion ? <BaselinePanel version={baseVersion} /> : null}
              <EmptyState
                align="start"
                title="Генератор у цьому семестрі ще не запускали."
                description="Хід розвʼязання зʼявиться тут наживо. Результат стане новою чернеткою, опублікований розклад не зміниться."
                className="py-0"
              />
            </div>
          )}
          {jobs.data && jobs.data.length > 0 ? <JobHistory jobs={jobs.data} selectedId={selectedJobId} onSelect={selectJob} /> : null}
        </div>
      </div>
    </div>
  );
}
