import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import RoomsPage from './RoomsPage';
import TeachersPage from './TeachersPage';

/**
 * На телефоні довідник — список рядків (`mobileRow`), а не стос таблиці: у стосі «⋯» займав шість рядків сітки
 * і рядок лише з прізвищем виходив на 69 px з кнопкою нижче за імʼя. Список у jsdom є поруч із таблицею
 * (ховає його CSS), тож перевіряємо саме його.
 */
describe('довідники на телефоні', () => {
  it('викладач — рядок списку з іменем і власною кнопкою дій', async () => {
    renderWithProviders(<TeachersPage />, { route: '/admin/catalog/teachers?q=Алексєєнко' });

    const list = await screen.findByRole('list', { name: 'Викладачі коледжу' });
    const item = within(list).getByText('Алексєєнко').closest('li') as HTMLElement;
    expect(within(item).getByRole('button', { name: 'Дії: Алексєєнко' })).toBeInTheDocument();
    // без «Підпис: значення» зі стосу таблиці
    expect(item).not.toHaveTextContent(/Вхід у систему/);
  });

  it('аудиторія — рядок списку з номером і кнопкою дій', async () => {
    renderWithProviders(<RoomsPage />, { route: '/admin/catalog/rooms?q=301' });

    const list = await screen.findByRole('list', { name: 'Аудиторії коледжу' });
    const item = within(list).getByText('301').closest('li') as HTMLElement;
    expect(within(item).getByRole('button', { name: 'Дії: аудиторія 301' })).toBeInTheDocument();
  });
});
