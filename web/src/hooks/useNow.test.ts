import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BellSlotDto } from '@/api/types';
import { resetClock, syncClock } from '@/lib/clock';
import { NOW_TICK_MS, useNow } from './useNow';

const BELLS: BellSlotDto[] = [
  { pair: 1, startsAt: '08:30', endsAt: '09:30' },
  { pair: 2, startsAt: '09:40', endsAt: '10:40' },
];

describe('useNow', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    resetClock();
    vi.useRealTimers();
  });

  it('показує поточну пару за київським часом і оновлюється з часом', () => {
    // 06:00 UTC 19.10.2026 = 09:00 у Києві: 1 пара, 30 хв до кінця
    vi.setSystemTime(new Date('2026-10-19T06:00:00Z'));
    const { result } = renderHook(() => useNow(BELLS));

    expect(result.current.clock.date).toBe('2026-10-19');
    expect(result.current.status).toMatchObject({ kind: 'in-pair', minutesLeft: 30 });

    act(() => {
      vi.setSystemTime(new Date('2026-10-19T06:35:00Z'));
      vi.advanceTimersByTime(NOW_TICK_MS);
    });
    expect(result.current.status).toMatchObject({ kind: 'break', minutesLeft: 5 });
  });

  it('не рахує стан пари для іншого дня', () => {
    vi.setSystemTime(new Date('2026-10-19T06:00:00Z'));
    const { result } = renderHook(() => useNow(BELLS, { date: '2026-10-20' }));
    expect(result.current.isToday).toBe(false);
    expect(result.current.status).toBeNull();
  });

  it('без дзвінків повертає лише час', () => {
    vi.setSystemTime(new Date('2026-10-19T06:00:00Z'));
    const { result } = renderHook(() => useNow());
    expect(result.current.status).toBeNull();
    expect(result.current.clock.minutes).toBe(9 * 60);
  });

  it('на стенді з переведеним часом рахує пару за годинником стенда, а realNow лишає справжнім', () => {
    // справжній час — субота 14:00; стенд — понеділок 19.10, 09:00
    vi.setSystemTime(new Date('2026-11-14T12:00:00Z'));
    const { result } = renderHook(() => useNow(BELLS));
    expect(result.current.status).toMatchObject({ kind: 'after-day' });

    act(() => {
      const real = Date.now();
      syncClock({ now: '2026-10-19T06:00:00Z', standTime: true }, real, real);
    });
    expect(result.current.clock.date).toBe('2026-10-19');
    expect(result.current.status).toMatchObject({ kind: 'in-pair', minutesLeft: 30 });
    expect(result.current.now.toISOString()).toBe('2026-10-19T06:00:00.000Z');
    expect(result.current.realNow.toISOString()).toBe('2026-11-14T12:00:00.000Z');
  });
});
