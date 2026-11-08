import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { OrderSummaryDto } from '@/api/types';
import { ConfirmDialogHost } from '@/components/ui/ConfirmDialog';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { OrdersArchive } from './OrdersArchive';

const imported: OrderSummaryDto = {
  id: 'o-85',
  number: 85,
  date: '2026-10-16',
  kind: 'ORDER',
  status: 'PUBLISHED',
  itemCount: 26,
  publishedAt: '2026-11-08T10:00:00Z',
  imported: true,
};

describe('архів розпоряджень: імпортоване з помилкою', () => {
  it('імпортоване розпорядження можна видалити (після підтвердження), щоб імпортувати правильне', async () => {
    const deleted = vi.fn();
    server.use(
      http.get(apiPath('/admin/orders'), () => HttpResponse.json({ items: [imported], page: 0, size: 10, total: 1 })),
      http.delete(apiPath('/admin/orders/:id'), ({ params }) => {
        deleted(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <OrdersArchive semester={null} selectedId={null} onOpen={() => undefined} />
        <ConfirmDialogHost />
      </>,
    );
    await user.click(await screen.findByRole('button', { name: /Дії.*№85/ }));
    expect(screen.queryByRole('menuitem', { name: /Завантажити PDF/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('menuitem', { name: 'Видалити імпортоване' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText(/№85 на 16\.10\.2026/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Видалити' }));

    await waitFor(() => expect(deleted).toHaveBeenCalledWith('o-85'));
  });
});
