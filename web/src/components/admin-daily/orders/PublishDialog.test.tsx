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
import { NOTHING_LEFT_OUT, type LeftOut } from './left-out';
import { PublishDialog, STALE_DRAFT_MESSAGE } from './PublishDialog';

const DAY = '2026-11-09';

/** Як на сторінці «Розпорядження»: діалог отримує поточну чернетку з запиту. */
function Harness({ onPublished, leftOut = NOTHING_LEFT_OUT }: { onPublished: (order: OrderDto) => void; leftOut?: LeftOut }) {
  const draft = useOrderDraft(DAY);
  const publish = usePublishOrder();
  const [open, setOpen] = useState(true);
  if (!draft.data) return <p>Завантажуємо…</p>;
  return (
    <>
      <p data-testid="sheet-rows">{draft.data.items.length}</p>
      {/* як на сторінці: діалог лишається змонтованим і закритим, кнопка в шапці відкриває його знову */}
      <button type="button" onClick={() => setOpen(true)}>
        Опублікувати з шапки
      </button>
      <PublishDialog
        open={open}
        onOpenChange={setOpen}
        order={draft.data}
        parentNumber={null}
        nextNumber={36}
        leftOut={leftOut}
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

  it('після «Переглянути чернетку» знову відкритий діалог не повторює старе попередження', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness onPublished={vi.fn()} />);
    let dialog = await screen.findByRole('dialog');

    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    const pending = changes.find((c) => c.status === 'PENDING_CONFIRMATION');
    if (!pending) throw new Error('у демо-ранку немає заміни, що чекає підтвердження');
    await api.post(`/admin/changes/${pending.id}/confirm`);

    await user.click(within(dialog).getByRole('button', { name: /Опублікувати/ }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(STALE_DRAFT_MESSAGE);
    await user.click(within(dialog).getByRole('button', { name: 'Переглянути чернетку' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    // диспетчер переглянув чернетку й відкриває публікацію знову
    await user.click(screen.getByRole('button', { name: 'Опублікувати з шапки' }));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Скасувати' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /Опублікувати/ })).not.toHaveAttribute('aria-busy', 'true');
  });

  it('Esc теж забуває відмову', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness onPublished={vi.fn()} />);
    let dialog = await screen.findByRole('dialog');
    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    const pending = changes.find((c) => c.status === 'PENDING_CONFIRMATION');
    if (!pending) throw new Error('у демо-ранку немає заміни, що чекає підтвердження');
    await api.post(`/admin/changes/${pending.id}/confirm`);

    await user.click(within(dialog).getByRole('button', { name: /Опублікувати/ }));
    await within(dialog).findByRole('alert');
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Опублікувати з шапки' }));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Скасувати' })).toBeInTheDocument();
  });
});

describe('PublishDialog: що не потрапить у документ', () => {
  it('пари без рішення — червоне попередження разом з очікуваннями й відмовами', async () => {
    renderWithProviders(<Harness onPublished={vi.fn()} leftOut={{ undecided: 3, pending: 1, declined: 1 }} />);
    const dialog = await screen.findByRole('dialog');
    const warning = within(dialog).getByText(/без рішення/).closest('p');
    expect(warning).toHaveTextContent(
      /^3\sпари без рішення, 1\sзаміна ще чекає підтвердження і 1 без нового кандидата після відмови — у цей документ не потраплять, лише в доповнення\.$/,
    );
    expect(warning).toHaveAttribute('data-tone', 'danger');
  });

  it('лише очікування — бурштинове, без пар без рішення', async () => {
    renderWithProviders(<Harness onPublished={vi.fn()} leftOut={{ undecided: 0, pending: 2, declined: 0 }} />);
    const dialog = await screen.findByRole('dialog');
    const warning = within(dialog).getByText(/чекають підтвердження/).closest('p');
    expect(warning).toHaveAttribute('data-tone', 'change');
    expect(within(dialog).queryByText(/без рішення/)).not.toBeInTheDocument();
  });
});
