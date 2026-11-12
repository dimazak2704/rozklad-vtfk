import { screen } from '@testing-library/react';
import type { UseQueryResult } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ScheduleDto } from '@/api/types';
import type { ScheduleParams } from '@/components/public/schedule/schedule-params';
import { renderWithProviders } from '@/test/render';
import { MyLessons } from './MyLessons';

const params: ScheduleParams = {
  date: '2026-11-12',
  today: '2026-11-12',
  homeDate: '2026-11-12',
  view: 'day',
  subgroup: null,
  week: { from: '2026-11-09', to: '2026-11-13' },
  setDate: vi.fn(),
  setView: vi.fn(),
  setSubgroup: vi.fn(),
};

// ще вантажиться: перевіряємо лише заголовок розділу з керуваннями
const pending = { data: undefined, isError: false, isPlaceholderData: false, isFetching: true } as unknown as UseQueryResult<ScheduleDto>;

function phone(matches: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: matches && query.includes('max-width: 639.98px'),
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MyLessons: керування тижнем', () => {
  it('на телефоні тиждень і «День / Тиждень» стоять одним рядком, дати — коротко', () => {
    phone(true);
    renderWithProviders(<MyLessons query={pending} params={params} weekNumber={11} />);
    const week = screen.getByRole('group', { name: 'Вибір тижня' });
    const view = screen.getByRole('radiogroup', { name: 'Подання розкладу' });
    expect(week.parentElement).toBe(view.parentElement);
    expect(week.parentElement).toHaveClass('justify-between');
    expect(week).toHaveTextContent('9–13 лист.');
  });

  it('на ширшому екрані — повна дата тижня', () => {
    phone(false);
    renderWithProviders(<MyLessons query={pending} params={params} weekNumber={11} />);
    expect(screen.getByRole('group', { name: 'Вибір тижня' })).toHaveTextContent('9–13 листопада');
  });
});
