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

  // r4-ai-imports-51: день тижня, парність і підпис можна виправити; розбіжність дня з датою видно одразу
  it('день тижня звіряється з датою, мітки й підпис ідуть на сервер', async () => {
    const sent = vi.fn();
    server.use(
      http.put(apiPath('/import/jobs/:id/draft'), async ({ request, params }) => {
        sent(await request.json());
        return HttpResponse.json({ id: params.id, kind: 'ORDER_DOCUMENT', status: 'READY', version: 4 });
      }),
    );
    const user = userEvent.setup();
    const read: OrderDraft = { ...headless, number: 30, date: '2026-10-15', weekdayLabel: "П'ЯТНИЦЯ", parityLabel: 'ЗНАМЕННИК', signatoryName: 'Микола ПОЛЩУК' };
    renderWithProviders(<OrderDraftEditor jobId="job-1" version={3} draft={read} onDone={vi.fn()} />);

    expect(screen.getByText('За датою це ЧЕТВЕР — перевірте дату або день тижня.')).toBeInTheDocument();
    const date = screen.getByLabelText(/^Дата/);
    await user.clear(date);
    await user.type(date, '2026-10-16');
    expect(screen.queryByText(/За датою це/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Зберегти звірені рядки' }));

    await waitFor(() => expect(sent).toHaveBeenCalledOnce());
    expect(sent).toHaveBeenCalledWith(expect.objectContaining({
      date: '2026-10-16',
      weekdayLabel: "П'ЯТНИЦЯ",
      parityLabel: 'ЗНАМЕННИК',
      signatoryName: 'Микола ПОЛЩУК',
    }));
  });

  // r5-ai-imports-73: правка тексту рядка повертає «За текстом», якщо тип не вибирали вручну
  it('після правки тексту тип рядка визначає сервер', async () => {
    const sent = vi.fn();
    server.use(
      http.put(apiPath('/import/jobs/:id/draft'), async ({ request, params }) => {
        sent(await request.json());
        return HttpResponse.json({ id: params.id, kind: 'ORDER_DOCUMENT', status: 'READY', version: 4 });
      }),
    );
    const user = userEvent.setup();
    const read: OrderDraft = {
      ...headless,
      number: 30,
      date: '2026-10-16',
      rows: [
        { section: 'CHANGES', groupCode: '1ОП1', pairLabel: '1', rowType: 'REPLACEMENT', fromDiscipline: 'ФВ', reasonText: 'Зв.зам.' },
        { section: 'CHANGES', groupCode: '2ОБ', pairLabel: '4', rowType: 'FINISHED', fromDiscipline: 'ФВ', reasonText: 'Вичит.' },
      ],
    };
    renderWithProviders(<OrderDraftEditor jobId="job-1" version={3} draft={read} onDone={vi.fn()} />);

    const reason = screen.getByRole('textbox', { name: 'Причина, рядок 1' });
    await user.clear(reason);
    await user.type(reason, 'Вичит.');
    await user.click(screen.getByRole('button', { name: 'Зберегти звірені рядки' }));

    await waitFor(() => expect(sent).toHaveBeenCalledOnce());
    const body = sent.mock.calls[0]?.[0] as { rows: Array<{ rowType: string | null }> };
    expect(body.rows.map((r) => r.rowType)).toEqual([null, 'FINISHED']);
  });
});
