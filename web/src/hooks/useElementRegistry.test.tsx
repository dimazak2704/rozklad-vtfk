import { act, render, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useElementRegistry, type ElementRegistry } from './useElementRegistry';

beforeEach(() => vi.useFakeTimers({ toFake: ['requestAnimationFrame'] }));
afterEach(() => vi.useRealTimers());

function List({ keys, registry }: { keys: string[]; registry: ElementRegistry }) {
  return (
    <ul>
      {keys.map((key) => (
        <li key={key}>
          <button type="button" ref={registry.register(key)}>
            {key}
          </button>
        </li>
      ))}
    </ul>
  );
}

describe('useElementRegistry', () => {
  it('тримає елементи за ключем і забуває розмонтовані', () => {
    const { result } = renderHook(() => useElementRegistry());
    const { rerender } = render(<List keys={['a', 'b']} registry={result.current} />);
    expect(result.current.get('a')?.textContent).toBe('a');
    expect(result.current.get('b')?.textContent).toBe('b');

    rerender(<List keys={['b']} registry={result.current} />);
    expect(result.current.get('a')).toBeNull();
    expect(result.current.get('b')?.textContent).toBe('b');
  });

  it('focusLater фокусує й докручує елемент лише після кадру', () => {
    const { result } = renderHook(() => useElementRegistry());
    render(<List keys={['a', 'b']} registry={result.current} />);
    const target = result.current.get('b');
    if (!target) throw new Error('b is not registered');
    const scroll = vi.spyOn(target, 'scrollIntoView');

    result.current.focusLater('b');
    expect(document.activeElement).not.toBe(target);

    act(() => {
      vi.advanceTimersToNextFrame();
    });
    expect(document.activeElement).toBe(target);
    expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
  });

  it('focusLater без елемента нічого не робить', () => {
    const { result } = renderHook(() => useElementRegistry());
    result.current.focusLater('missing');
    expect(() =>
      act(() => {
        vi.advanceTimersToNextFrame();
      }),
    ).not.toThrow();
  });
});
