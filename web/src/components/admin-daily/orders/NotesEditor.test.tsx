/**
 * Примітки розпорядження і журнал дій: Ctrl+Z повертає попередні примітки, а редактор після цього
 * показує їх як збережені — без «Зберегти примітки» над тим, що вже на сервері.
 */
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useOrderDraft } from '@/api/admin-daily';
import { api } from '@/api/client';
import type { OrderDto } from '@/api/types';
import { undoLatest, redoLatest } from '@/lib/undo/actions';
import { useJournal } from '@/lib/undo/journal';
import { renderWithProviders } from '@/test/render';
import { NotesEditor } from './NotesEditor';

const DAY = '2026-11-09';

/** Як на сторінці «Розпорядження»: чернетка з сервера, редактор з ключем за id і текстом приміток. */
function Harness() {
  const draft = useOrderDraft(DAY);
  if (!draft.data) return <p>Завантажуємо…</p>;
  return <NotesEditor key={`${draft.data.id}:${draft.data.notes.join('\n')}`} order={draft.data} />;
}

const noteFields = () => screen.queryAllByRole('textbox').map((field) => (field as HTMLTextAreaElement).value);
const serverNotes = async () => (await api.get<OrderDto>('/admin/orders/draft', { date: DAY })).notes;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-09T05:40:00Z'));
  useJournal.getState().clear();
  useJournal.getState().setPathScope('/admin/orders');
});
afterEach(() => vi.useRealTimers());

describe('NotesEditor і журнал дій', () => {
  it('скасування збереження повертає старі примітки, і редактор не показує їх незбереженими', async () => {
    const user = userEvent.setup();
    const before = await serverNotes();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole('button', { name: 'Додати примітку' }));
    await user.type(screen.getAllByRole('textbox').at(-1) as HTMLElement, 'Групи 2КІ3 – практика');
    await user.click(screen.getByRole('button', { name: 'Зберегти примітки' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Зберегти примітки' })).toBeNull());

    await undoLatest('/admin/orders');
    expect(useJournal.getState().entries[0]).toMatchObject({ status: 'undone' });
    expect(await serverNotes()).toEqual(before);
    await waitFor(() => expect(noteFields()).toEqual(before));
    expect(screen.queryByRole('button', { name: 'Зберегти примітки' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Скасувати зміни' })).toBeNull();

    // і повтор так само: примітка знову є й збережена
    await redoLatest('/admin/orders');
    expect(useJournal.getState().entries[0]).toMatchObject({ status: 'done' });
    await waitFor(() => expect(noteFields()).toEqual([...before, 'Групи 2КІ3 – практика']));
    expect(screen.queryByRole('button', { name: 'Зберегти примітки' })).toBeNull();
  });

  it('незбережена правка в редакторі блокує скасування — і про неї журнал знає навіть після перемалювання', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole('button', { name: 'Додати примітку' }));
    await user.type(screen.getAllByRole('textbox').at(-1) as HTMLElement, 'Перша');
    await user.click(screen.getByRole('button', { name: 'Зберегти примітки' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Зберегти примітки' })).toBeNull());

    // нова незбережена правка в уже перемальованому редакторі
    await user.type(screen.getAllByRole('textbox').at(-1) as HTMLElement, ' і ще');
    await undoLatest('/admin/orders');
    expect(useJournal.getState().entries[0]).toMatchObject({ status: 'failed', reason: 'Спершу збережіть або скасуйте зміни в примітках' });
    expect(noteFields().at(-1)).toBe('Перша і ще');
  });
});
