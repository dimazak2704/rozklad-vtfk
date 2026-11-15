import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { GroupTheoryDto } from '@/api/types';
import { GroupTheoryList, GroupTheoryNote } from './GroupTheoryNote';

const ns = {
  id: 'p-ns',
  kind: { code: 'НС', name: 'Національний спротив', color: null, effect: 'SUSPEND' as const },
  title: 'Основи національного спротиву',
  startsOn: '2026-11-30',
  endsOn: '2026-12-11',
};
const winter = {
  id: null,
  kind: { code: 'К', name: 'Канікули', color: '#FFFFCC', effect: 'DAY_OFF' as const },
  title: 'Канікули',
  startsOn: '2026-12-28',
  endsOn: '2026-12-30',
};
const ki2: GroupTheoryDto = {
  group: { id: 'g-4ki2', code: '4КІ2', course: 4 },
  semesterWeeks: 18,
  theoryWeeks: 15,
  numeratorWeeks: 8,
  denominatorWeeks: 7,
  studyDays: 74,
  graphTheoryWeeks: 20,
  offPeriods: [ns, winter],
};
const ki3: GroupTheoryDto = { ...ki2, group: { id: 'g-4ki3', code: '4КІ3', course: 4 }, graphTheoryWeeks: 15, offPeriods: [] };

describe('GroupTheoryNote', () => {
  it('показує тижні теорії групи, періоди з кодом виду й назвою в підказці та примітку, коли графік каже інше', () => {
    render(<GroupTheoryNote theory={ki2} />);
    expect(screen.getByText('4КІ2')).toBeInTheDocument();
    const nsCode = screen.getByText(/^НС /);
    expect(nsCode.tagName).toBe('ABBR');
    expect(nsCode).toHaveAttribute('title', expect.stringContaining('Основи національного спротиву'));
    expect(screen.getByText(/^К /)).toBeInTheDocument();
    expect(screen.getByText(/графік/i)).toBeInTheDocument();
  });

  it('без періодів і з тим самим числом у графіку — лише рядок тижнів, без примітки', () => {
    const { container } = render(<GroupTheoryNote theory={ki3} />);
    expect(container.querySelectorAll('abbr')).toHaveLength(0);
    expect(container.querySelectorAll('p')).toHaveLength(1);
    expect(container.textContent).not.toContain(' — ');
  });
});

describe('GroupTheoryList', () => {
  it('у згорнутому списку лічить групи з іншим числом у графіку й пояснює розбіжність', async () => {
    render(<GroupTheoryList items={[ki2, ki3]} />);
    const summary = screen.getByText(/Тижні теорії груп/);
    await userEvent.click(summary);
    expect(screen.getByText(/у графіку 20/)).toBeInTheDocument();
    expect(screen.queryByText(/у графіку 15/)).not.toBeInTheDocument();
    expect(screen.getByText(/План рахується за календарем системи/)).toBeInTheDocument();
  });

  it('коли графік скрізь збігається, пояснення розбіжності немає', async () => {
    render(<GroupTheoryList items={[ki3]} />);
    await userEvent.click(screen.getByText(/Тижні теорії груп/));
    expect(screen.queryByText(/План рахується за календарем системи/)).not.toBeInTheDocument();
  });
});
