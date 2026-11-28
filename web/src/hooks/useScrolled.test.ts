import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useScrolled } from './useScrolled';

function scrollTo(y: number) {
  Object.defineProperty(window, 'scrollY', { configurable: true, value: y });
}

afterEach(() => {
  scrollTo(0);
});

describe('useScrolled', () => {
  it('не читає прокрутку під час рендеру: перша перевірка — перед найближчим кадром', () => {
    const frames: FrameRequestCallback[] = [];
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    const scrollY = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(120);

    const { result } = renderHook(() => useScrolled());

    expect(scrollY).not.toHaveBeenCalled();
    expect(result.current).toBe(false);
    act(() => frames.forEach((callback) => callback(0)));
    expect(result.current).toBe(true);
  });

  it('стежить за прокруткою з порогом', () => {
    const { result } = renderHook(() => useScrolled(4));

    act(() => {
      scrollTo(10);
      window.dispatchEvent(new Event('scroll'));
    });
    expect(result.current).toBe(true);

    act(() => {
      scrollTo(3);
      window.dispatchEvent(new Event('scroll'));
    });
    expect(result.current).toBe(false);
  });

  it('після розмонтування не слухає прокрутку й скасовує кадр', () => {
    const cancel = vi.spyOn(window, 'cancelAnimationFrame');
    const removed = vi.spyOn(window, 'removeEventListener');

    const { unmount } = renderHook(() => useScrolled());
    unmount();

    expect(cancel).toHaveBeenCalled();
    expect(removed).toHaveBeenCalledWith('scroll', expect.any(Function));
  });
});
