import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useStalled, type StallableQuery } from './useStalled';

const pending: StallableQuery = { isPending: true, fetchStatus: 'fetching' };
const paused: StallableQuery = { isPending: true, fetchStatus: 'paused' };
const disabled: StallableQuery = { isPending: true, fetchStatus: 'idle' };
const done: StallableQuery = { isPending: false, fetchStatus: 'idle' };

describe('useStalled', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('пауза без мережі — одразу «offline»', () => {
    const { result } = renderHook(() => useStalled([done, paused]));
    expect(result.current).toBe('offline');
  });

  it('довге очікування стає «slow» лише після порогу, а відповідь його знімає', () => {
    const { result, rerender } = renderHook(({ q }) => useStalled([q], 1000), { initialProps: { q: pending } });
    expect(result.current).toBeNull();
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current).toBe('slow');
    rerender({ q: done });
    expect(result.current).toBeNull();
    // новий запит знову чекає повний поріг
    rerender({ q: pending });
    expect(result.current).toBeNull();
  });

  it('вимкнений запит не рахується', () => {
    const { result } = renderHook(() => useStalled([disabled, null], 10));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    expect(result.current).toBeNull();
  });
});
