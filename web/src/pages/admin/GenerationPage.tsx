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
import type { LoadProblemDto, Uuid } from '@/api/types';
import { BaselinePanel } from '@/components/generation/BaselinePanel';
import { GenerationForm } from '@/components/generation/GenerationForm';
import { JobHistory } from '@/components/generation/JobHistory';
import { JobPanel } from '@/components/generation/JobPanel';
import {
  completionNotice,
  formFromJob,
  initialForm,
  jobHasParams,
  toJobRequest,
  validateForm,
  type GenerationFormState,
} from '@/components/generation/model';
import { publishedVersion } from '@/components/timetable/model/versions';
import { Button, EmptyState, LoadingRegion, notify, PageErrorBanner, PageHeader, Skeleton } from '@/components/ui';
import { useRegisterCommands } from '@/stores/commands';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';

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
  const [loadProblems, setLoadProblems] = useState<readonly LoadProblemDto[]>([]);
  useGenerationLiveUpdates(selectedJobId);

  const published = publishedVersion(versions.data ?? []);
  const activeJob = isJobActive(job.data?.status) ? job.data : (jobs.data ?? []).find((j) => isJobActive(j.status));
  const busy = Boolean(activeJob);
  // поки задача йде, ліва панель показує її власні параметри, а не форму (genfix B8)
  const showsJob = Boolean(activeJob && jobHasParams(activeJob));
  const form = activeJob && showsJob ? formFromJob(activeJob) : (draft ?? initialForm(published?.id ?? null));
  const baseVersion = (versions.data ?? []).find((v) => v.id === form.baseVersionId) ?? published;
  const versionNames = new Map((versions.data ?? []).map((v) => [v.id, v.name]));
  const jobBase = job.data?.baseVersionId ? (versions.data ?? []).find((v) => v.id === job.data?.baseVersionId) : undefined;

  const editForm = (next: GenerationFormState) => {
    setDraft(next);
    setLoadProblems([]);
  };

  useServerEvent('generation.completed', (event) => {
    // з жорсткими конфліктами — попередження, а не зелена галочка (genfix B6)
    const notice = completionNotice(event);
    if (notice) notify[notice.level](notice.title, { description: notice.description });
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
    setLoadProblems([]);
    try {
      const created = await start.mutateAsync(toJobRequest(form, semesterId));
      selectJob(created.jobId);
      notify.info('Генерацію запущено', {
        description: 'Хід розвʼязання видно на графіку; сторінку можна закрити — задача працює на сервері.',
      });
    } catch (error) {
      // 422: навантаження не вміщується в сітку — список того, що саме, лишається під формою
      if (isApiError(error) && error.problems.length > 0) setLoadProblems(error.problems);
      const fields = isApiError(error) ? error.errors.map((e) => e.message).join('; ') : '';
      notify.error(error, 'Генерацію не запущено', fields ? { description: fields } : undefined);
    }
  };

  // задача, що розвʼязується, зупиняється з найкращим варіантом; у черзі — скасовується без чернетки
  const stop = (jobId: Uuid) =>
    cancel.mutate(jobId, {
      onSuccess: (stopped) =>
        stopped.status === 'CANCELLED'
          ? notify.info('Генерацію скасовано', { description: 'Розклад ще не склався, тож чернетку не створено.' })
          : notify.info('Зупиняємо генерацію', { description: 'Найкращий варіант на цей момент збережеться як чернетка.' }),
      onError: (error) => notify.error(error, 'Не вдалося зупинити генерацію'),
    });

  useRegisterCommands([
    {
      id: 'start-generation',
      label: 'Запустити генерацію розкладу',
      group: 'Генератор',
      icon: Cpu,
      shortcut: 'mod+enter',
      perform: () => void launch(),
    },
  ]);
  useHotkeys([
    {
      keys: 'mod+enter',
      handler: () => void launch(),
      description: 'Запустити генерацію',
      group: 'Генератор',
      allowInInputs: true,
      enabled: !start.isPending,
    },
  ]);

  const formReady = !(meta.isPending || versions.isPending || catalog.isPending);
  // головна дія — наприкінці панелі «Параметри», де людина щойно все налаштувала (§11.4);
  // з будь-якого місця її запускає ще й палітра команд
  const launchButton = (
    <Button
      variant="primary"
      onClick={() => void launch()}
      disabled={busy || !formReady || !semesterId}
      loading={start.isPending}
      loadingText="Запускаємо…"
      iconStart={<Play aria-hidden="true" className="size-4" />}
    >
      Запустити генерацію
    </Button>
  );
  const header = <PageHeader title="Генератор розкладу" />;

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

  // задача скінчилася — на вузькому екрані її результат іде першим, над усією формою параметрів (і в порядку
  // фокуса теж); на широкому колонки лишаються на своїх місцях
  const resultFirst = Boolean(job.data && !isJobActive(job.data.status));
  const formColumn = (
    <div data-generation-form className={cn('min-w-0', resultFirst && 'lg:col-start-1 lg:row-start-1')}>
      {!formReady ? (
        <LoadingRegion label="Завантажуємо параметри…" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
          {Array.from({ length: 7 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </LoadingRegion>
      ) : (
        <GenerationForm
          form={form}
          onChange={editForm}
          groups={catalog.data?.groups ?? []}
          versions={versions.data ?? []}
          busy={busy}
          submitted={submitted}
          submit={launchButton}
          title={showsJob ? 'Параметри запуску' : 'Параметри'}
          loadProblems={loadProblems}
        />
      )}
    </div>
  );

  return (
    <div className="flex flex-col gap-6">
      {header}
      <div className="grid items-start gap-6 lg:grid-cols-[22rem_minmax(0,1fr)] 2xl:grid-cols-[26rem_minmax(0,1fr)]">
        {resultFirst ? null : formColumn}

        <div data-generation-result className={cn('flex min-w-0 flex-col gap-6', resultFirst && 'lg:col-start-2 lg:row-start-1')}>
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
              base={jobBase ?? null}
              cancelling={cancel.isPending}
              onCancel={() => job.data && stop(job.data.id)}
              onUseParams={
                job.data && jobHasParams(job.data)
                  ? () => {
                      if (job.data) editForm(formFromJob(job.data));
                      notify.info('Параметри перенесено у форму', { description: 'Змініть, що треба, і запустіть генерацію.' });
                    }
                  : undefined
              }
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
          {jobs.data && jobs.data.length > 0 ? <JobHistory jobs={jobs.data} selectedId={selectedJobId} onSelect={selectJob} versionNames={versionNames} /> : null}
        </div>
        {resultFirst ? formColumn : null}
      </div>
    </div>
  );
}
