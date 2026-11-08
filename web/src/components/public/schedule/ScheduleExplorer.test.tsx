import type { UseQueryResult } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ScheduleDto } from '@/api/types';
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
