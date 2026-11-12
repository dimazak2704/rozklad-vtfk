import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useImportJob, type ImportJob } from './import';
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
