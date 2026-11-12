/**
 * Публікація застарілої чернетки (409 STALE_DRAFT у core): клієнт перечитує чернетку, діалог каже
 * «Чернетку оновлено…» і показує свіжу кількість рядків; друга публікація проходить.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useOrderDraft, usePublishOrder } from '@/api/admin-daily';
import { api } from '@/api/client';
import type { ChangeDto, OrderDto } from '@/api/types';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import { PublishDialog, STALE_DRAFT_MESSAGE } from './PublishDialog';

const DAY = '2026-11-09';

/** Як на сторінці «Розпорядження»: діалог отримує поточну чернетку з запиту. */
function Harness({ onPublished }: { onPublished: (order: OrderDto) => void }) {
  const draft = useOrderDraft(DAY);
  const publish = usePublishOrder();
  const [open, setOpen] = useState(true);
  if (!draft.data) return <p>Завантажуємо…</p>;
  return (
    <>
      <p data-testid="sheet-rows">{draft.data.items.length}</p>
      <PublishDialog
        open={open}
        onOpenChange={setOpen}
        order={draft.data}
        parentNumber={null}
        nextNumber={36}
        pending={0}
        declined={0}
        onPublish={async () => {
          const published = await publish.mutateAsync(draft.data.id);
          onPublished(published);
          return published;
        }}
      />
    </>
  );
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-09T05:40:00Z'));
  // демо-ранок мока будується від поточного часу: скидаємо світ уже з підробленою датою
  resetMockStores();
});
afterEach(() => vi.useRealTimers());

describe('PublishDialog: чернетка застаріла', () => {
  it('409 STALE_DRAFT перечитує чернетку, пояснює й дає опублікувати свіжу', async () => {
    const user = userEvent.setup();
    const onPublished = vi.fn();
    renderWithProviders(<Harness onPublished={onPublished} />);
    const dialog = await screen.findByRole('dialog');
    const rowsBefore = Number(screen.getByTestId('sheet-rows').textContent);

    // поки діалог відкритий, викладач підтвердив ще одну заміну (подію SSE пропущено)
    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    const pending = changes.find((c) => c.status === 'PENDING_CONFIRMATION');
    if (!pending) throw new Error('у демо-ранку немає заміни, що чекає підтвердження');
    await api.post(`/admin/changes/${pending.id}/confirm`);

    await user.click(within(dialog).getByRole('button', { name: /Опублікувати/ }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(STALE_DRAFT_MESSAGE);
    expect(onPublished).not.toHaveBeenCalled();
    // чернетку перечитано: за діалогом і в ньому — свіжі рядки
    await waitFor(() => expect(Number(screen.getByTestId('sheet-rows').textContent)).toBeGreaterThan(rowsBefore));
    expect(within(dialog).getByText(new RegExp(`${rowsBefore + 1} рядк`))).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Переглянути чернетку' })).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Опублікувати/ }));
    await waitFor(() => expect(onPublished).toHaveBeenCalledTimes(1));
    expect(onPublished.mock.calls[0]?.[0]).toMatchObject({ status: 'PUBLISHED' });
  });
});
