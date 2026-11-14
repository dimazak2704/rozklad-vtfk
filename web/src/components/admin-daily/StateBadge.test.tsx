import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { PendingCountdown } from './StateBadge';

// 3 пара 11:00–12:20 за Києвом (UTC+2 у листопаді): кінець — 10:20Z; строк відповіді — 09:00Z
const DEADLINE = '2026-11-09T09:00:00Z';
const LESSON = { date: '2026-11-09', endsAt: '12:20' } as const;

beforeEach(() => vi.useFakeTimers({ toFake: ['Date'] }));
afterEach(() => vi.useRealTimers());

describe('PendingCountdown', () => {
  it('строк минув, а пара ще йде: червоне «прострочено»', () => {
    vi.setSystemTime(new Date('2026-11-09T09:30:00Z'));
    renderWithProviders(<PendingCountdown deadline={DEADLINE} lesson={LESSON} />);
    expect(screen.getByText(/^прострочено 30/)).toHaveClass('text-danger');
  });

  it('пара вже минула: спокійне «пара минула» замість «прострочено»', () => {
    vi.setSystemTime(new Date('2026-11-09T18:10:00Z'));
    renderWithProviders(<PendingCountdown deadline={DEADLINE} lesson={LESSON} />);
    expect(screen.getByText('пара минула')).not.toHaveClass('text-danger');
    expect(screen.queryByText(/прострочено/)).not.toBeInTheDocument();
  });
});
