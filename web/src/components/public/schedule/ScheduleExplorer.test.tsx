import type { UseQueryResult } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePublicSchedule } from '@/api/public';
import { resetSavedCopies } from '@/api/saved-copy';
import { SAVED_COPY_HEADER } from '@/api/saved-copy-plugin';
import type { ScheduleDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { DEMO_WEEK } from '@/pages/dev/demo-data';
import { renderWithProviders } from '@/test/render';
import type { ScheduleParams } from './schedule-params';
import { ScheduleExplorer } from './ScheduleExplorer';

function params(date: string, from: string, to: string): ScheduleParams {
  return {
    date,
    today: '2026-11-09',
    homeDate: '2026-11-09',
    view: 'week',
    subgroup: null,
    week: { from, to },
    setDate: vi.fn(),
    setView: vi.fn(),
    setSubgroup: vi.fn(),
  };
}

/** Стан запиту так, як його віддає TanStack Query: дані, чи це дані попереднього ключа, і чи запит на паузі. */
function query(data: ScheduleDto | undefined, state: { placeholder: boolean; paused: boolean }): UseQueryResult<ScheduleDto> {
  return {
    data,
    isPlaceholderData: state.placeholder,
    fetchStatus: state.paused ? 'paused' : 'idle',
    isError: false,
    error: null,
    isFetching: false,
    refetch: vi.fn(),
  } as unknown as UseQueryResult<ScheduleDto>;
}

describe('ScheduleExplorer без мережі', () => {
  it('тиждень, який ще не переглядали, не підміняє розкладом попереднього тижня', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    renderWithProviders(
      <ScheduleExplorer
        query={query(DEMO_WEEK, { placeholder: true, paused: true })}
        params={params('2026-11-16', '2026-11-16', '2026-11-20')}
        perspective="group"
        ownerLabel="4КІ1"
      />,
    );
    expect(screen.getByText('Цей тиждень не збережено на пристрої')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'До поточного тижня' })).toBeInTheDocument();
    // ні сітки попереднього тижня, ні обіцянки «показано збережений розклад»
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByText(/Показано збережений розклад/)).not.toBeInTheDocument();
  });

  it('уже переглянутий тиждень показує зі збереженого й попереджає про мережу', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    renderWithProviders(
      <ScheduleExplorer
        query={query(DEMO_WEEK, { placeholder: false, paused: true })}
        params={params('2026-11-09', '2026-11-09', '2026-11-13')}
        perspective="group"
        ownerLabel="4КІ1"
      />,
    );
    expect(screen.getByText(/Немає інтернету\. Показано збережений розклад/)).toBeInTheDocument();
    expect(screen.queryByText('Цей тиждень не збережено на пристрої')).not.toBeInTheDocument();
  });

  it('перший відкритий без мережі розклад — пояснення замість нескінченного завантаження', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    renderWithProviders(
      <ScheduleExplorer
        query={query(undefined, { placeholder: false, paused: true })}
        params={params('2026-11-09', '2026-11-09', '2026-11-13')}
        perspective="group"
        ownerLabel="4КІ1"
      />,
    );
    expect(screen.getByText('Цей тиждень не збережено на пристрої')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'До поточного тижня' })).not.toBeInTheDocument();
  });
});

describe('ScheduleExplorer: тиждень без пар', () => {
  it('група на практиці бачить назву практики, а не «канікули або дні поза семестром»', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    const practice: ScheduleDto = {
      ...DEMO_WEEK,
      days: DEMO_WEEK.days.map((d) => ({ ...d, studyDay: false, note: 'Електрорадіомонтажна практика 2КІ3', lessons: [] })),
    };
    renderWithProviders(
      <ScheduleExplorer
        query={query(practice, { placeholder: false, paused: false })}
        params={params('2026-11-09', '2026-11-09', '2026-11-13')}
        perspective="group"
        ownerLabel="2КІ3"
      />,
    );
    expect(screen.getByText('Цього тижня занять немає')).toBeInTheDocument();
    expect(screen.getByText(/Електрорадіомонтажна практика 2КІ3\. Розклад на інші тижні/)).toBeInTheDocument();
    expect(screen.queryByText(/канікули або дні поза семестром/)).not.toBeInTheDocument();
  });
});

describe('ScheduleExplorer: копія з кешу Service Worker, коли мережа є', () => {
  afterEach(() => resetSavedCopies());

  /** Справжній запит розкладу: клієнт API бачить позначку Service Worker у відповіді. */
  function LiveSchedule() {
    const p = params('2026-11-09', '2026-11-09', '2026-11-13');
    const schedule = usePublicSchedule('GROUP', DEMO_WEEK.owner.id, p.week.from, p.week.to);
    return <ScheduleExplorer query={schedule} params={p} perspective="group" ownerLabel="4КІ1" />;
  }

  it('сервер не відповів і Service Worker віддав стару копію: кажемо про це з датою копії, свіжа відповідь прибирає примітку', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    let fromCache = true;
    server.use(
      http.get(apiPath('/public/schedule/:type/:id'), () =>
        HttpResponse.json(DEMO_WEEK, {
          headers: fromCache ? { [SAVED_COPY_HEADER]: new Date('2026-11-05T06:15:00Z').toUTCString() } : {},
        }),
      ),
    );
    renderWithProviders(<LiveSchedule />);

    const note = (await screen.findByText(/Сервер не відповів/)).closest('[role="status"]');
    expect(note).toHaveTextContent(
      // toHaveTextContent зводить нерозривний пробіл до звичайного
      'Сервер не відповів. Показано розклад, збережений 5 листопада о 08:15 — заміни могли змінитися.',
    );

    fromCache = false;
    await userEvent.click(screen.getByRole('button', { name: 'Оновити' }));
    await vi.waitFor(() => expect(screen.queryByText(/Сервер не відповів/)).not.toBeInTheDocument());
  });

  it('звичайна відповідь сервера — без примітки', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    let served = false;
    server.use(
      http.get(apiPath('/public/schedule/:type/:id'), () => {
        served = true;
        return HttpResponse.json(DEMO_WEEK);
      }),
    );
    renderWithProviders(<LiveSchedule />);
    await vi.waitFor(() => expect(served).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByText(/Показано/)).not.toBeInTheDocument();
  });
});
