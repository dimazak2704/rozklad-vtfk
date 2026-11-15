import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { AbsenceList } from './AbsenceList';

describe('список відсутніх', () => {
  it('на телефоні поруч із «Додати відсутність» є «Нова зміна» (у шапці її там немає)', async () => {
    const user = userEvent.setup();
    const onAddChange = vi.fn();
    const props = { absences: [], entries: [], selectedId: null, onSelect: vi.fn(), onAdd: vi.fn(), loading: false, drill: true };
    const { rerender } = renderWithProviders(<AbsenceList {...props} onAddChange={onAddChange} />);
    await user.click(screen.getByRole('button', { name: 'Нова зміна' }));
    expect(onAddChange).toHaveBeenCalledOnce();
    // на ширшому екрані кнопка в шапці — у списку не дублюємо
    rerender(<AbsenceList {...props} />);
    expect(screen.queryByRole('button', { name: 'Нова зміна' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Додати відсутність' })).toBeInTheDocument();
  });
});
