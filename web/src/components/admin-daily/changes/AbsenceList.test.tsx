import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { AbsenceDto } from '@/api/types';
import { FIXTURE_LESSON, FIXTURE_TEACHERS } from '@/mocks/fixtures/lesson-options';
import { renderWithProviders } from '@/test/render';
import type { LessonEntry, LessonState } from '../model';
import { absenceCaption } from './absence-caption';
import { AbsenceList, AbsenceProgress } from './AbsenceList';

const absence: AbsenceDto = {
  id: 'a-1',
  teacher: FIXTURE_TEACHERS.krysak,
  dateFrom: '2026-11-20',
  dateTo: '2026-11-20',
  pairs: null,
  reasonCategory: 'SICK_LEAVE',
  comment: null,
  source: 'BOT',
  status: 'ACTIVE',
  createdBy: 'admin',
  createdAt: '2026-11-20T04:00:00Z',
  version: 0,
};

const entry = (id: string, state: LessonState): LessonEntry => ({
  absence,
  item: { date: '2026-11-20', lesson: { ...FIXTURE_LESSON, id }, decision: null, asSubstitute: false },
  state,
  change: null,
});

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

describe('коментар викладача в списку відсутніх', () => {
  const props = { entries: [], selectedId: null, onSelect: vi.fn(), onAdd: vi.fn(), loading: false };

  it('«Інше: що саме» видно диспетчеру одразу, без діалогу', () => {
    renderWithProviders(<AbsenceList {...props} absences={[{ ...absence, reasonCategory: 'OTHER', comment: '  Курси підвищення кваліфікації ' }]} />);
    expect(screen.getByText('«Курси підвищення кваліфікації»')).toBeInTheDocument();
  });

  it('без коментаря — без зайвого рядка', () => {
    renderWithProviders(<AbsenceList {...props} absences={[{ ...absence, comment: '   ' }]} />);
    expect(screen.queryByText(/«/)).not.toBeInTheDocument();
  });
});

describe('скільки пар відсутності вирішено', () => {
  it('пара, що чекає відповіді, — не «усе вирішено»: бурштиновий відрізок і «1 чекає відповіді»', () => {
    const { container } = renderWithProviders(<AbsenceProgress entries={[entry('l3', 'pending')]} />);
    expect(screen.getByText('1 чекає відповіді')).toBeInTheDocument();
    expect(screen.queryByText('усе вирішено')).not.toBeInTheDocument();
    expect(container.querySelector('[data-segment="waiting"]')).not.toBeNull();
  });

  it('очікування рахується окремо від вирішених', () => {
    renderWithProviders(<AbsenceProgress entries={[entry('l1', 'confirmed'), entry('l2', 'pending'), entry('l3', 'uncovered')]} />);
    expect(screen.getByText(/вирішено/).closest('span')).toHaveTextContent(/^вирішено 1 з 3 пар · 1 чекає відповіді$/);
  });

  it('усе вирішено — лише коли нічого не чекаємо', () => {
    renderWithProviders(<AbsenceProgress entries={[entry('l1', 'confirmed'), entry('l2', 'applied')]} />);
    expect(screen.getByText('усе вирішено')).toBeInTheDocument();
  });
});

describe('підпис відсутності', () => {
  it('у поданні «Тиждень» одноденна — з днем і датою; кількаденна — діапазоном, як і було', () => {
    expect(absenceCaption(absence)).toBe('увесь день · лікарняний');
    expect(absenceCaption(absence, true)).toBe('Пт, 20.11 · увесь день · лікарняний');
    expect(absenceCaption({ ...absence, dateTo: '2026-11-23' }, true)).toMatch(/^20.*23\sлистопада · лікарняний$/);
  });
});
