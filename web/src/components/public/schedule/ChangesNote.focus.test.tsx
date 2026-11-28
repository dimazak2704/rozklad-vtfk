import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { ChangesNote } from './ChangesNote';
import type { ChangedLesson } from './schedule-utils';

const change = { day: { date: '2026-11-10' } } as ChangedLesson;

describe('ChangesNote: перехід до першої зміни', () => {
  it('тиждень: фокус на першу видиму змінену клітинку в обгортці сітки', async () => {
    const region = createRef<HTMLDivElement>();
    const onOpenDay = vi.fn();
    renderWithProviders(
      <>
        <div ref={region}>
          <button type="button">
            <span>Математика</span>
          </button>
          <button type="button">
            <span className="text-change">Заміна</span>
          </button>
        </div>
        <ChangesNote items={[change]} view="week" weekRegion={region} onOpenDay={onOpenDay} currentWeek />
      </>,
    );
    // jsdom не рахує розкладку: клітинка «видима», якщо має прямокутник
    const cell = screen.getByRole('button', { name: 'Заміна' });
    vi.spyOn(cell, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList);

    await userEvent.setup().click(screen.getByText('1 заміна цього тижня'));
    expect(cell).toHaveFocus();
    expect(onOpenDay).not.toHaveBeenCalled();
  });

  it('без зміненої клітинки на екрані відкриває день першої зміни', async () => {
    const region = createRef<HTMLDivElement>();
    const onOpenDay = vi.fn();
    renderWithProviders(
      <>
        <div ref={region} />
        <ChangesNote items={[change]} view="week" weekRegion={region} onOpenDay={onOpenDay} currentWeek />
      </>,
    );
    await userEvent.setup().click(screen.getByText('1 заміна цього тижня'));
    expect(onOpenDay).toHaveBeenCalledWith('2026-11-10');
  });
});
