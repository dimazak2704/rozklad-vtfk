import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { OrderDraft } from '@/api/import';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { OrderDraftEditor } from './OrderDraftEditor';

/** Фото без шапки: ШІ не прочитав ні номера, ні дати. */
const headless: OrderDraft = {
  number: null,
  kind: 'ORDER',
  date: null,
  rows: [{ section: 'CHANGES', groupCode: '4КІ1', pairLabel: '2', rowType: 'REPLACEMENT', fromDiscipline: 'СТП' }],
  notes: [],
  source: 'AI',
  reviewed: false,
};

describe('звірка заголовка розпорядження', () => {
  it('номер, дату й вид можна виправити, і вони йдуть на сервер', async () => {
    const sent = vi.fn();
    server.use(
      http.put(apiPath('/import/jobs/:id/draft'), async ({ request, params }) => {
        sent(await request.json());
        return HttpResponse.json({ id: params.id, kind: 'ORDER_DOCUMENT', status: 'READY', version: 4 });
      }),
    );
    const user = userEvent.setup();
    const onDone = vi.fn();
    renderWithProviders(<OrderDraftEditor jobId="job-1" version={3} draft={headless} onDone={onDone} />);

    await user.type(screen.getByRole('textbox', { name: /Номер/ }), '35');
    await user.type(screen.getByLabelText(/Дата/), '2026-10-19');
    await user.click(screen.getByRole('button', { name: 'Зберегти звірені рядки' }));

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(sent).toHaveBeenCalledWith(expect.objectContaining({
      version: 3,
      number: 35,
      date: '2026-10-19',
      kind: 'ORDER',
      parentNumber: null,
    }));
  });

  it('порожній номер надсилається як null, а не 0', async () => {
    const sent = vi.fn();
    server.use(
      http.put(apiPath('/import/jobs/:id/draft'), async ({ request, params }) => {
        sent(await request.json());
        return HttpResponse.json({ id: params.id, kind: 'ORDER_DOCUMENT', status: 'NEEDS_REVIEW', version: 4 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<OrderDraftEditor jobId="job-1" version={3} draft={headless} onDone={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: 'Зберегти звірені рядки' }));

    await waitFor(() => expect(sent).toHaveBeenCalledOnce());
    expect(sent).toHaveBeenCalledWith(expect.objectContaining({ number: null, date: null }));
  });
});
