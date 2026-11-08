import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import RoomsPage from './RoomsPage';

describe('сторінка «Аудиторії»', () => {
  it('нова аудиторія: помилка форми, потім дубль із сервера, потім успіх', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RoomsPage />, { route: '/admin/catalog/rooms' });
    expect(await screen.findByRole('rowheader', { name: '301' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Додати аудиторію' }));
    const dialog = await screen.findByRole('dialog', { name: 'Нова аудиторія' });
    const submit = within(dialog).getByRole('button', { name: 'Додати аудиторію' });
    const name = within(dialog).getByRole('textbox', { name: /Назва/ });

    await user.click(submit);
    expect(await within(dialog).findByText('Вкажіть назву або номер аудиторії')).toBeInTheDocument();
    expect(name).toHaveAttribute('aria-invalid', 'true');

    await user.type(name, '301');
    await user.click(submit);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Аудиторія «301» уже існує.');
    expect(name).toHaveValue('301');

    await user.clear(name);
    await user.type(name, '512');
    await user.click(submit);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Нова аудиторія' })).not.toBeInTheDocument());

    await user.type(screen.getByRole('searchbox', { name: 'Пошук аудиторії' }), '512');
    expect(await screen.findByRole('rowheader', { name: '512' })).toBeInTheDocument();
  });

  it('видалення використаної аудиторії пояснює 409 і пропонує зробити її неактивною', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RoomsPage />, { route: '/admin/catalog/rooms?q=301' });
    const row = (await screen.findByRole('rowheader', { name: '301' })).closest('tr') as HTMLElement;

    await user.click(within(row).getByRole('button', { name: 'Дії: аудиторія 301' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Видалити' }));
    const confirmDialog = await screen.findByRole('alertdialog', { name: 'Видалити аудиторію?' });
    await user.click(within(confirmDialog).getByRole('button', { name: 'Видалити' }));

    const blocked = await screen.findByRole('alertdialog', { name: 'Видалити не можна' });
    expect(blocked).toHaveTextContent('вона є в розкладі, навантаженні або закріплена за групою');
    await user.click(within(blocked).getByRole('button', { name: 'Позначити неактивною' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(await within(row).findByText('Неактивна')).toBeInTheDocument();
  });
});
