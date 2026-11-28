import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { createRef } from 'react';
import { renderWithProviders } from '@/test/render';
import { ChangesNote } from './ChangesNote';
import { isHomeWeek } from './schedule-params';
import type { ChangedLesson } from './schedule-utils';

// рядку потрібні лише кількість і день першої зміни
const change = { day: { date: '2026-11-10' } } as ChangedLesson;

describe('ChangesNote', () => {
  it('поточний тиждень — «цього тижня»', () => {
    renderWithProviders(<ChangesNote items={[change, change]} view="week" weekRegion={createRef()} onOpenDay={() => {}} currentWeek />);
    expect(screen.getByRole('button')).toHaveTextContent('2 заміни цього тижня');
  });

  it('інший тиждень — нейтральне «на тижні», без «цього тижня»', () => {
    renderWithProviders(<ChangesNote items={[change]} view="week" weekRegion={createRef()} onOpenDay={() => {}} currentWeek={false} />);
    expect(screen.getByRole('button')).toHaveTextContent('1 заміна на тижні');
    expect(screen.queryByText(/цього тижня/)).not.toBeInTheDocument();
  });

  it('isHomeWeek: найближчий навчальний день усередині тижня на екрані', () => {
    const week = { from: '2026-11-16', to: '2026-11-20' };
    expect(isHomeWeek({ homeDate: '2026-11-19', week })).toBe(true);
    expect(isHomeWeek({ homeDate: '2026-11-13', week })).toBe(false);
    expect(isHomeWeek({ homeDate: '2026-11-23', week })).toBe(false);
  });
});
