import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { screen } from '@testing-library/react';
import { TodayChanges } from './TodayChanges';
import { homeDayWords, useHomeDay } from './useHomeDay';

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Київський час через підмінений Date (таймери справжні: React Query й MSW працюють як завжди). */
function at(instant: string): void {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(instant));
}

afterEach(() => {
  vi.useRealTimers();
});

describe('useHomeDay', () => {
  it('поки йдуть пари — сьогодні', async () => {
    at('2026-11-12T10:00:00Z'); // четвер, 12:00 у Києві
    const { result } = renderHook(() => useHomeDay(), { wrapper });
    await waitFor(() => expect(result.current.today).toBe('2026-11-12'));
    // дзвінки вже прийшли, а день той самий
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current).toEqual({ date: '2026-11-12', today: '2026-11-12', isToday: true });
  });

  it('після останнього дзвінка — наступний навчальний день, як і в реченні героя', async () => {
    at('2026-11-12T18:06:00Z'); // четвер, 20:06 у Києві: пари скінчилися о 18:10
    const { result } = renderHook(() => useHomeDay(), { wrapper });
    await waitFor(() => expect(result.current.date).toBe('2026-11-13'));
    expect(result.current).toEqual({ date: '2026-11-13', today: '2026-11-12', isToday: false });
  });

  it('у пʼятницю ввечері — понеділок, а не субота', async () => {
    at('2026-11-13T17:30:00Z'); // пʼятниця, 19:30 у Києві
    const { result } = renderHook(() => useHomeDay(), { wrapper });
    await waitFor(() => expect(result.current.date).toBe('2026-11-16'));
  });

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

describe('homeDayWords', () => {
  it('сьогодні й завтра — словом, далі — днем тижня з датою', () => {
    expect(homeDayWords({ date: '2026-11-12', today: '2026-11-12', isToday: true })).toEqual({ when: 'сьогодні', onDay: 'на сьогодні', dated: false });
    expect(homeDayWords({ date: '2026-11-13', today: '2026-11-12', isToday: false })).toEqual({ when: 'завтра', onDay: 'на завтра', dated: false });
    expect(homeDayWords({ date: '2026-11-16', today: '2026-11-13', isToday: false })).toEqual({
      when: 'на понеділок, 16 листопада',
      onDay: 'на понеділок, 16 листопада',
      dated: true,
    });
  });
});

describe('«Заміни» на головній увечері', () => {
  it('після пар питають заміни на завтра й називають день у реченні про розпорядження', async () => {
    at('2026-11-12T18:06:00Z');
    const asked: string[] = [];
    server.use(
      http.get(apiPath('/public/changes'), ({ request }) => {
        const date = new URL(request.url).searchParams.get('date') ?? '';
        asked.push(date);
        return HttpResponse.json({ date, parity: 'NUMERATOR', studyDay: true, weekdayLabel: 'Пʼятниця', order: null, amendments: [], groups: [] });
      }),
    );
    renderWithProviders(<TodayChanges />);
    expect(await screen.findByText('Розпорядження на завтра ще не опубліковано. Поки що діє звичайний розклад.')).toBeInTheDocument();
    expect(asked.at(-1)).toBe('2026-11-13');
    expect(screen.getByRole('link', { name: 'Усі заміни' })).toHaveAttribute('href', '/changes?date=2026-11-13');
  });
});
