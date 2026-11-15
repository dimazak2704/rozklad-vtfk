import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { CalendarWeek } from '@/api/calendar';
import { renderWithProviders } from '@/test/render';
import { SemesterTimeline } from './SemesterTimeline';

const weeks = [
  {
    weekNumber: 1,
    weekStart: '2026-08-31',
    weekEnd: '2026-09-04',
    parity: 'NUMERATOR',
    computedParity: 'NUMERATOR',
    overridden: false,
  },
  {
    weekNumber: 2,
    weekStart: '2026-09-07',
    weekEnd: '2026-09-11',
    parity: 'DENOMINATOR',
    computedParity: 'DENOMINATOR',
    overridden: false,
  },
] as unknown as CalendarWeek[];

describe('тижні семестру', () => {
  it('натиск на тиждень лише відкриває меню — парність міняє окрема дія', async () => {
    const user = userEvent.setup();
    const toggle = vi.fn();
    renderWithProviders(<SemesterTimeline weeks={weeks} periods={[]} today="2026-09-02" onToggleWeek={toggle} onOpenPeriod={() => undefined} />);
    const [week] = screen.getAllByRole('button', {
      name: /^1 тиждень.*Меню тижня/,
    });
    await user.click(week as HTMLElement);
    expect(toggle).not.toHaveBeenCalled();
    await user.click(await screen.findByRole('menuitem', { name: 'Зробити знаменником…' }));
    expect(toggle).toHaveBeenCalledWith(weeks[0]);
  });

  it('під час збереження дія недоступна', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SemesterTimeline weeks={weeks} periods={[]} today="2026-09-02" onToggleWeek={null} onOpenPeriod={() => undefined} />);
    await user.click(
      screen.getAllByRole('button', {
        name: /^2 тиждень.*Меню тижня/,
      })[0] as HTMLElement,
    );
    expect(await screen.findByRole('menuitem', { name: 'Зробити чисельником…' })).toHaveAttribute('aria-disabled', 'true');
  });

  it('підказка тижня (дати, парність) зʼявляється на фокусі, хоч кнопка й відкриває меню', async () => {
    renderWithProviders(<SemesterTimeline weeks={weeks} periods={[]} today="2026-09-02" onToggleWeek={vi.fn()} onOpenPeriod={() => undefined} />);
    // друга кнопка тижня — у сітці для широкого екрана (перша — у списку для телефона, без підказки)
    const gridWeek = screen.getAllByRole('button', { name: /^2 тиждень.*Меню тижня/ })[1] as HTMLElement;
    act(() => gridWeek.focus());
    expect(await screen.findByRole('tooltip')).toHaveTextContent('2 тиждень, 7–11 вересня: знаменник.');
  });
});
