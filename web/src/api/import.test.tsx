import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useApplyImport, useImportJob, useImportJobs, watchApply, type ImportJob } from './import';
import { qk } from './queryKeys';

const JOB_ID = '00000000-0000-4000-8000-0000000000aa';

function job(status: ImportJob['status']): ImportJob {
  // хук читає лише стан; решта полів для цієї перевірки не потрібна
  return { id: JOB_ID, kind: 'TIMETABLE_XLSX', status, report: [] } as unknown as ImportJob;
}

describe('useImportJob: завершення застосування', () => {
  it('після APPLYING → APPLIED оновлює і публічні довідники (редактор і вибір викладача), і кабінет', async () => {
    const statuses: ImportJob['status'][] = ['APPLYING', 'APPLIED'];
    server.use(http.get(apiPath('/import/jobs/:id'), () => HttpResponse.json(job(statuses.length > 1 ? statuses.shift()! : statuses[0]!))));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(client, 'invalidateQueries');
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

    const { result } = renderHook(() => useImportJob(JOB_ID), { wrapper });
    await waitFor(() => expect(result.current.data?.status).toBe('APPLIED'), { timeout: 5000 });

    const keys = spy.mock.calls.map(([filters]) => filters?.queryKey);
    expect(keys).toEqual(expect.arrayContaining([qk.admin.all, qk.public.all, qk.me.all]));
  });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const spy = vi.spyOn(client, 'invalidateQueries');
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const invalidated = () => spy.mock.calls.map(([filters]) => filters?.queryKey);
  return { client, wrapper, invalidated };
}

function page(...items: ImportJob[]) {
  return { items, page: 0, size: 20, total: items.length };
}

describe('useImportJobs: завершення застосування видно й зі списку', () => {
  it('диспетчер повернувся до «Усі імпорти» під час APPLYING: список бачить APPLIED і оновлює довідники й кабінет', async () => {
    const pages = [page(job('APPLYING')), page(job('APPLIED'))];
    server.use(http.get(apiPath('/import/jobs'), () => HttpResponse.json(pages.length > 1 ? pages.shift()! : pages[0]!)));
    const { wrapper, invalidated } = setup();

    const { result } = renderHook(() => useImportJobs(), { wrapper });
    await waitFor(() => expect(result.current.data?.items[0]?.status).toBe('APPLIED'), { timeout: 5000 });

    expect(invalidated()).toEqual(expect.arrayContaining([qk.admin.all, qk.public.all, qk.me.all]));
    // свій же список не інвалідуємо — інакше опитування скасовувало б саме себе
    expect(invalidated()).not.toContainEqual(qk.importer.jobs());
  });

  it('завдання, що зникло зі сторінки фільтра APPLYING, теж вважаємо завершеним', async () => {
    const pages = [page(job('APPLYING')), page()];
    server.use(http.get(apiPath('/import/jobs'), () => HttpResponse.json(pages.length > 1 ? pages.shift()! : pages[0]!)));
    const { wrapper, invalidated } = setup();

    const { result } = renderHook(() => useImportJobs({ status: 'APPLYING' }), { wrapper });
    await waitFor(() => expect(result.current.data?.items).toHaveLength(1));
    await waitFor(() => expect(result.current.data?.items).toHaveLength(0), { timeout: 5000 });
    expect(invalidated()).toContainEqual(qk.public.all);
  });

  it('без переходу з APPLYING нічого зайвого не оновлює', async () => {
    server.use(http.get(apiPath('/import/jobs'), () => HttpResponse.json(page(job('READY')))));
    const { client, wrapper, invalidated } = setup();

    const { result } = renderHook(() => useImportJobs(), { wrapper });
    await waitFor(() => expect(result.current.data?.items[0]?.status).toBe('READY'));
    await act(() => client.refetchQueries({ queryKey: qk.importer.jobs() }));
    expect(invalidated()).not.toContainEqual(qk.public.all);
  });
});

describe('застосування без жодної сторінки імпорту', () => {
  it('після «Застосувати» спостерігач доводить завдання до APPLIED і оновлює довідники, хоч хук уже розмонтовано', async () => {
    const statuses: ImportJob['status'][] = ['APPLYING', 'APPLIED'];
    server.use(
      http.post(apiPath('/import/jobs/:id/apply'), () => HttpResponse.json(job('APPLYING'), { status: 202 })),
      http.get(apiPath('/import/jobs/:id'), () => HttpResponse.json(job(statuses.length > 1 ? statuses.shift()! : statuses[0]!))),
    );
    const { client, wrapper, invalidated } = setup();

    const { result, unmount } = renderHook(() => useApplyImport(JOB_ID), { wrapper });
    await act(() => result.current.mutateAsync());
    // диспетчер одразу пішов зі сторінки: ні JobReview, ні списку немає
    unmount();

    await waitFor(() => expect(client.getQueryData<ImportJob>(qk.importer.job(JOB_ID))?.status).toBe('APPLIED'), { timeout: 8000 });
    await waitFor(() => expect(invalidated()).toEqual(expect.arrayContaining([qk.admin.all, qk.public.all, qk.me.all])));
  });

  it('сервер застосував одразу (відповідь уже APPLIED) — довідники оновлюються без опитування', async () => {
    server.use(http.post(apiPath('/import/jobs/:id/apply'), () => HttpResponse.json(job('APPLIED'), { status: 202 })));
    const { wrapper, invalidated } = setup();

    const { result } = renderHook(() => useApplyImport(JOB_ID), { wrapper });
    await act(() => result.current.mutateAsync());
    expect(invalidated()).toEqual(expect.arrayContaining([qk.public.all, qk.me.all]));
  });

  it('спостерігач здається після кількох помилок поспіль і не крутиться вічно', async () => {
    let calls = 0;
    server.use(
      http.get(apiPath('/import/jobs/:id'), () => {
        calls += 1;
        return HttpResponse.json({ status: 503 }, { status: 503 });
      }),
    );
    const { client } = setup();
    await watchApply(client, '00000000-0000-4000-8000-0000000000bb', { pollMs: 1 });
    expect(calls).toBe(5);
  });
});
