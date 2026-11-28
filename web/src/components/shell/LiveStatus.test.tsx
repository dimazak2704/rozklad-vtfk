import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EventStreamState } from '@/api/useEventStream';
import { LiveStatus, RECONNECTING_GRACE_MS } from './LiveStatus';

const state = (status: EventStreamState['status']): EventStreamState => ({
  status,
  lastEventAt: null,
  failures: 1,
});

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('стан звʼязку у верхній панелі', () => {
  it('коротке перепідключення (перший вхід, обрив на секунду) не блимає «Перепідключення…»', () => {
    const { rerender } = render(<LiveStatus state={state('reconnecting')} />);
    expect(screen.queryByText('Перепідключення…')).not.toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(RECONNECTING_GRACE_MS - 100);
    });
    rerender(<LiveStatus state={state('open')} />);
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(screen.queryByText('Перепідключення…')).not.toBeInTheDocument();
  });

  it('холодний старт: поки потік жодного разу не відкрився, довге перепідключення теж не показуємо', () => {
    const { rerender } = render(<LiveStatus state={state('connecting')} />);
    rerender(<LiveStatus state={state('reconnecting')} />);
    act(() => {
      vi.advanceTimersByTime(RECONNECTING_GRACE_MS * 3);
    });
    expect(screen.queryByText('Перепідключення…')).not.toBeInTheDocument();
    // так і не підключилися — це вже «Немає звʼязку», і воно видно одразу
    rerender(<LiveStatus state={state('closed')} />);
    expect(screen.getByText('Немає звʼязку')).toBeInTheDocument();
  });

  it('довге — тихе слово; щойно звʼязок є — зникає, а новий обрив знову чекає паузу', () => {
    const { rerender } = render(<LiveStatus state={state('open')} />);
    rerender(<LiveStatus state={state('reconnecting')} />);
    act(() => {
      vi.advanceTimersByTime(RECONNECTING_GRACE_MS + 10);
    });
    expect(screen.getByText('Перепідключення…')).toBeInTheDocument();
    rerender(<LiveStatus state={state('open')} />);
    expect(screen.queryByText('Перепідключення…')).not.toBeInTheDocument();
    rerender(<LiveStatus state={state('reconnecting')} />);
    expect(screen.queryByText('Перепідключення…')).not.toBeInTheDocument();
  });

  it('звʼязку немає зовсім — одразу, з «Повторити»', () => {
    render(<LiveStatus state={state('closed')} />);
    expect(screen.getByText('Немає звʼязку')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Повторити' }).length).toBeGreaterThan(0);
  });
});
