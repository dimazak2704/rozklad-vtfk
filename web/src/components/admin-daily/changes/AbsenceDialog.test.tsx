/**
 * «Змінити відсутність» і 409 `stale-data`: діалог перечитує відсутність із сервера, підставляє свіжі
 * дані в поля, яких диспетчер не чіпав, і друге «Зберегти зміни» проходить з новою версією.
 */
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { STALE_DATA_MESSAGE } from '@/api/errors';
import type { AbsenceDto, AbsenceRequest } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { AbsenceDialog } from './AbsenceDialog';

const request = (absence: AbsenceDto, patch: Partial<AbsenceRequest> = {}): AbsenceRequest => ({
  teacherId: absence.teacher.id,
  dateFrom: absence.dateFrom,
  dateTo: absence.dateTo,
  pairs: absence.pairs,
  reasonCategory: absence.reasonCategory,
  comment: absence.comment,
  version: absence.version,
  ...patch,
});

async function someAbsence(): Promise<AbsenceDto> {
  const list = await api.get<AbsenceDto[]>('/admin/absences', { from: '2026-09-01', to: '2026-12-30' });
  const absence = list[0];
  if (!absence) throw new Error('у моку немає відсутностей');
  return absence;
}

function renderDialog(absence: AbsenceDto, onSaved = vi.fn()) {
  renderWithProviders(
    <AbsenceDialog open onOpenChange={() => undefined} date={absence.dateFrom} semester={null} absence={absence} onSaved={onSaved} />,
  );
  return onSaved;
}

describe('AbsenceDialog: відсутність щойно змінив хтось інший', () => {
  it('після 409 підставляє свіжі дані, а друге «Зберегти зміни» проходить', async () => {
    const user = userEvent.setup();
    const snapshot = await someAbsence();
    // колега змінив коментар, поки диспетчер тримав діалог відкритим
    const foreign = await api.put<AbsenceDto>(`/admin/absences/${snapshot.id}`, request(snapshot, { comment: 'Уточнив колега' }));
    const onSaved = renderDialog(snapshot);

    await user.click(screen.getByRole('button', { name: 'Зберегти зміни' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(STALE_DATA_MESSAGE);
    // поле, якого диспетчер не чіпав, тепер показує свіже значення з сервера
    await waitFor(() => expect(screen.getByLabelText('Коментар')).toHaveValue('Уточнив колега'));
    expect(onSaved).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Зберегти зміни' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    const [saved, created, before] = onSaved.mock.calls[0] as [AbsenceDto, boolean, AbsenceDto | null];
    expect(created).toBe(false);
    expect(saved.version).toBe(foreign.version + 1);
    expect(saved.comment).toBe('Уточнив колега');
    // журнал скасовуватиме до свіжого стану, а не до знімка зі сторінки
    expect(before).toMatchObject({ id: snapshot.id, version: foreign.version, comment: 'Уточнив колега' });
  });

  it('свою правку диспетчера не губить: змінене ним поле лишається, решта — свіжі', async () => {
    const user = userEvent.setup();
    const snapshot = await someAbsence();
    // колега змінив причину, а диспетчер тим часом пише коментар
    const reason = snapshot.reasonCategory === 'SICK_LEAVE' ? 'BUSINESS_TRIP' : 'SICK_LEAVE';
    await api.put(`/admin/absences/${snapshot.id}`, request(snapshot, { reasonCategory: reason }));
    const onSaved = renderDialog(snapshot);

    const comment = screen.getByLabelText('Коментар');
    await user.clear(comment);
    await user.type(comment, 'Подзвонив о 7:40');
    await user.click(screen.getByRole('button', { name: 'Зберегти зміни' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(STALE_DATA_MESSAGE);
    await waitFor(() => expect(screen.getByRole('combobox', { name: /Причина/ })).toHaveTextContent(reason === 'SICK_LEAVE' ? 'Лікарняний' : 'Відрядження'));
    expect(comment).toHaveValue('Подзвонив о 7:40');

    await user.click(screen.getByRole('button', { name: 'Зберегти зміни' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(onSaved.mock.calls[0]?.[0]).toMatchObject({ comment: 'Подзвонив о 7:40', reasonCategory: reason });
  });

  it('відсутність тим часом скасували — каже про це прямо, а не «повторіть дію»', async () => {
    const user = userEvent.setup();
    const snapshot = await someAbsence();
    await api.put(`/admin/absences/${snapshot.id}`, request(snapshot, { comment: 'Уточнив колега' }));
    await api.delete(`/admin/absences/${snapshot.id}`);
    renderDialog(snapshot);

    await user.click(screen.getByRole('button', { name: 'Зберегти зміни' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Цю відсутність уже скасували');
  });
});
