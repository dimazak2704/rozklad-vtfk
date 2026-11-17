import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CatalogTeacher } from '@/api/catalog';
import { teachers } from '@/mocks/handlers/catalog';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import { TeacherFormDialog } from './TeacherForm';

vi.setConfig({ testTimeout: 20_000 });
beforeEach(() => resetMockStores());

function krentsin(): CatalogTeacher {
  const found = teachers.all().find((t) => t.lastName === 'Кренцін');
  if (!found) throw new Error('немає Кренціна у фікстурах');
  return found;
}

function renderForm(teacher: CatalogTeacher) {
  const onOpenChange = vi.fn();
  renderWithProviders(<TeacherFormDialog teacher={teacher} open onOpenChange={onOpenChange} />, { route: '/admin/catalog/teachers' });
  return onOpenChange;
}

describe('форма викладача: зміна ПІБ', () => {
  it('нове прізвище переходить у «Як у розкладі» й «Публічне імʼя» і зберігається', async () => {
    const user = userEvent.setup();
    const teacher = krentsin();
    const onOpenChange = renderForm(teacher);

    const lastName = await screen.findByRole('textbox', { name: /Прізвище/ });
    await user.clear(lastName);
    await user.type(lastName, 'Кренцин');
    const firstName = screen.getByRole('textbox', { name: 'Імʼя' });
    await user.clear(firstName);
    await user.type(firstName, 'Микола');

    expect(screen.getByRole('textbox', { name: 'Як у розкладі' })).toHaveValue('Кренцин');
    expect(screen.getByRole('textbox', { name: 'Публічне імʼя' })).toHaveValue('Кренцин');
    expect(screen.getAllByText(/^Оновлено разом з/)).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    expect(teachers.find(teacher.id)).toMatchObject({
      lastName: 'Кренцин',
      firstName: 'Микола',
      middleName: 'Дмитрович',
      shortName: 'Кренцин',
      displayName: 'Кренцин',
    });
  });

  it('написане вручну коротке імʼя більше не йде за прізвищем, а розбіжність підказано', async () => {
    const user = userEvent.setup();
    renderForm(krentsin());

    const short = await screen.findByRole('textbox', { name: 'Як у розкладі' });
    await user.clear(short);
    await user.type(short, 'Кренцін М. Д.');
    const lastName = screen.getByRole('textbox', { name: /Прізвище/ });
    await user.clear(lastName);
    await user.type(lastName, 'Коваль');

    expect(short).toHaveValue('Кренцін М. Д.');
    expect(screen.getByText('Прізвище тут інше, ніж вище. Так буде в розкладі.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Публічне імʼя' })).toHaveValue('Коваль');
  });

  it('коротке імʼя, яке вже є в іншого викладача, — повідомлення сервера у формі', async () => {
    const user = userEvent.setup();
    const other = teachers.all().find((t) => t.lastName !== 'Кренцін');
    if (!other) throw new Error('потрібен інший викладач');
    renderForm(krentsin());

    const short = await screen.findByRole('textbox', { name: 'Як у розкладі' });
    await user.clear(short);
    await user.type(short, other.shortName);
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));

    expect(await screen.findByText(`Викладач «${other.shortName}» уже існує.`, { exact: false })).toBeInTheDocument();
  });
});
