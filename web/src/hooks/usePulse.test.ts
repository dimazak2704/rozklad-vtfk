import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { usePulse } from './usePulse';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('usePulse', () => {
  it('id пульсує заданий час і зникає', () => {
    const { result } = renderHook(() => usePulse<string>(1_000));
    act(() => result.current[1]('a'));
    expect(result.current[0].has('a')).toBe(true);
    act(() => {
      vi.advanceTimersByTime(999);
    });
    expect(result.current[0].has('a')).toBe(true);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current[0].has('a')).toBe(false);
  });

  it('розмонтування скасовує таймери', () => {
    const clear = vi.spyOn(window, 'clearTimeout');
    const { result, unmount } = renderHook(() => usePulse<string>(1_000));
    act(() => {
      result.current[1]('a');
      result.current[1]('b');
    });
    unmount();
    expect(clear).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });
});
