/**
 * Область «Генератор розкладу» (CONTRACTS §2 Generation): запуск задачі Timefold, хід розвʼязання
 * (опитування + SSE `generation.progress` / `generation.completed`), скасування, результат.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { useServerEvent } from './live';
import { qk } from './queryKeys';
import type {
  GenerationJobCreated,
  GenerationJobDto,
  GenerationJobRequest,
  GenerationJobStatus,
  GenerationProgressPoint,
  GenerationWeights,
  Uuid,
} from './types';

/** Ваги за замовчуванням — ті самі, що підставляє core для `weights: null` (GenerationWeights.DEFAULT). */
export const DEFAULT_WEIGHTS: Readonly<GenerationWeights> = {
  windows: 10,
  brokenDays: 10,
  latePairs: 3,
  preferences: 5,
  homeRoom: 1,
};

/** Межі ліміту часу з контракту (timeLimitSeconds 10..600). */
export const TIME_LIMIT_MIN = 10;
export const TIME_LIMIT_MAX = 600;

const ACTIVE: ReadonlySet<GenerationJobStatus> = new Set(['QUEUED', 'RUNNING']);

export function isJobActive(status: GenerationJobStatus | undefined): boolean {
  return status !== undefined && ACTIVE.has(status);
}

/** Рядок оцінки Timefold `0hard/-312soft` → числа. Невідомий формат — null (показуємо рядок як є). */
export function parseScore(score: string | null | undefined): { hard: number; soft: number } | null {
  if (!score) return null;
  const match = /^\s*(-?\d+)hard\/(-?\d+)soft\s*$/i.exec(score);
  if (!match) return null;
  return { hard: Number(match[1]), soft: Number(match[2]) };
}

const jobsPath = '/admin/generation/jobs';
const jobPath = (jobId: Uuid) => `${jobsPath}/${encodeURIComponent(jobId)}`;

export function useGenerationJobs(semesterId: Uuid | undefined) {
  return useQuery({
    queryKey: qk.admin.generation.jobs(semesterId),
    queryFn: ({ signal }) => api.get<GenerationJobDto[]>(jobsPath, { semesterId }, { signal }),
    enabled: Boolean(semesterId),
    // поки щось розвʼязується, історія теж оновлюється (стан, час, оцінка)
    refetchInterval: (query) => (query.state.data?.some((job) => isJobActive(job.status)) ? 4000 : false),
  });
}

/** Задача; поки вона в черзі чи розвʼязується — опитуємо раз на 2 с (SSE пришвидшує, але не замінює). */
export function useGenerationJob(jobId: Uuid | null | undefined) {
  return useQuery({
    queryKey: qk.admin.generation.job(jobId ?? ''),
    queryFn: ({ signal }) => api.get<GenerationJobDto>(jobPath(jobId ?? ''), undefined, { signal }),
    enabled: Boolean(jobId),
    refetchInterval: (query) => (isJobActive(query.state.data?.status) ? 2000 : false),
    refetchIntervalInBackground: false,
  });
}

export function useStartGeneration() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: GenerationJobRequest) => api.post<GenerationJobCreated>(jobsPath, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.admin.generation.all }),
  });
}

export function useCancelGeneration() {
  const queryClient = useQueryClient();
  return useMutation({
    // 202 із задачею (повторне скасування CANCELLED — те саме; SOLVED/FAILED → 409)
    mutationFn: (jobId: Uuid) => api.post<GenerationJobDto>(`${jobPath(jobId)}/cancel`),
    onSettled: (_data, _error, jobId) => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.generation.job(jobId) });
      void queryClient.invalidateQueries({ queryKey: qk.admin.generation.jobs() });
    },
  });
}

/** Додає точку прогресу, якщо вона новіша за останню (SSE і опитування можуть принести ту саму). */
export function appendProgressPoint(
  progress: readonly GenerationProgressPoint[],
  point: GenerationProgressPoint,
): GenerationProgressPoint[] {
  const last = progress.at(-1);
  if (last && point.t <= last.t) return [...progress];
  return [...progress, point];
}

/**
 * Живі події задачі: `generation.progress` дописує точку в кеш одразу, `generation.completed` кладе
 * фінальний стан. Зʼєднання SSE тримає макет адмінки — тут лише підписка.
 */
export function useGenerationLiveUpdates(jobId: Uuid | null | undefined): void {
  const queryClient = useQueryClient();

  useServerEvent('generation.progress', (event) => {
    if (!jobId || event.jobId !== jobId) return;
    queryClient.setQueryData<GenerationJobDto>(qk.admin.generation.job(jobId), (job) => {
      if (!job || !isJobActive(job.status)) return job;
      const point = { t: Math.round(event.elapsedMs / 100) / 10, hard: event.hardScore, soft: event.softScore };
      return {
        ...job,
        status: 'RUNNING',
        bestScore: `${event.hardScore}hard/${event.softScore}soft`,
        progress: appendProgressPoint(job.progress, point),
      };
    });
  });

  useServerEvent('generation.completed', (event) => {
    if (!jobId || event.id !== jobId) return;
    queryClient.setQueryData<GenerationJobDto>(qk.admin.generation.job(jobId), (job) => ({ ...job, ...event }));
    void queryClient.invalidateQueries({ queryKey: qk.admin.generation.jobs() });
  });
}
