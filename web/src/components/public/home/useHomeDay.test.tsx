import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useHomeDay } from './useHomeDay';

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('useHomeDay', () => {
  it('у канікули веде на наступний навчальний день від сервера, а не на найближчий будній', async () => {
    server.use(
      http.get(apiPath('/public/meta'), () =>
        HttpResponse.json({
          collegeName: 'ВТФК',
          semester: null,
          today: '2026-12-31',
          weekNumber: null,
          parity: null,
          studyDay: false,
          currentPair: null,
          nextPair: null,
          bellProfile: null,
          nextStudyDay: { date: '2027-01-19', weekday: 2, weekNumber: 1, parity: 'NUMERATOR' },
        }),
      ),
    );
    const { result } = renderHook(() => useHomeDay(), { wrapper });

    await waitFor(() => expect(result.current.today).toBe('2026-12-31'));
    expect(result.current).toEqual({ date: '2027-01-19', today: '2026-12-31', isToday: false });
  });
});
