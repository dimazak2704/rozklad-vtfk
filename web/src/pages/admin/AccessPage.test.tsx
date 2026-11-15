import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmDialogHost } from '@/components/ui';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import AccessPage from './AccessPage';

vi.setConfig({ testTimeout: 20_000 });

function renderPage() {
  return renderWithProviders(
    <>
      <AccessPage />
      <ConfirmDialogHost />
    </>,
    { route: '/admin/access' },
  );
}

async function rowOf(name: string): Promise<HTMLElement> {
  const cell = await screen.findByText(name);
  const row = cell.closest<HTMLElement>('tr') ?? cell.closest<HTMLElement>('li') ?? cell.parentElement;
  if (!row) throw new Error(`немає рядка ${name}`);
  return row;
}

describe('Доступи', () => {
  it('показує людей з ролями, станом і способом входу', async () => {
    renderPage();
    await screen.findByText('Диспетчер Розкладу');
    const table = screen.getByRole('table', { name: 'Хто може увійти' });
    expect(within(table).getByText('former.dispatcher@vtc.vn.ua')).toBeInTheDocument();
    expect(within(table).getAllByText('Відкликано')).toHaveLength(1);
    expect(within(table).getAllByText('Активний').length).toBeGreaterThan(1);
    expect(within(table).getAllByText('демо').length).toBe(3);
  });

  it('надати доступ викладачеві: потрібен викладач; зайнята адреса — помилка у формі', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: 'Хто може увійти' });
    await user.click(screen.getByRole('button', { name: 'Надати доступ' }));
    const dialog = await screen.findByRole('dialog', { name: 'Надати доступ' });
    await user.type(within(dialog).getByLabelText(/E-mail/), 'krentsin@rozklad.local');
    await user.click(within(dialog).getByRole('button', { name: 'Надати доступ' }));
    expect(await within(dialog).findByText('Виберіть викладача')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('radio', { name: 'Адміністратор' }));
    await user.type(within(dialog).getByLabelText(/Ім’я/), 'Ще один диспетчер');
    server.use(http.post(apiPath('/admin/access'), () => problem({ status: 409, code: 'EMAIL_TAKEN', detail: 'Ця адреса вже має доступ.' })));
    await user.click(within(dialog).getByRole('button', { name: 'Надати доступ' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Ця адреса вже має доступ.');
  });

  it('надати доступ адміністраторові за e-mail', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: 'Хто може увійти' });
    await user.click(screen.getByRole('button', { name: 'Надати доступ' }));
    const dialog = await screen.findByRole('dialog', { name: 'Надати доступ' });
    await user.click(within(dialog).getByRole('radio', { name: 'Адміністратор' }));
    await user.type(within(dialog).getByLabelText(/E-mail/), 'deputy@vtc.vn.ua');
    await user.type(within(dialog).getByLabelText(/Ім’я/), 'Заступник директора');
    await user.click(within(dialog).getByRole('button', { name: 'Надати доступ' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Надати доступ' })).not.toBeInTheDocument());
    expect(await screen.findByText('Заступник директора')).toBeInTheDocument();
  });

  it('відкликати доступ — після підтвердження з поясненням; повернути — з меню рядка', async () => {
    const user = userEvent.setup();
    renderPage();
    const row = await rowOf('М. В. Непийвода');
    await user.click(within(row).getByRole('button', { name: /Дії/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Відкликати доступ' }));
    const confirmDialog = await screen.findByRole('alertdialog');
    expect(confirmDialog).toHaveTextContent('коли працівник іде з коледжу');
    await user.click(within(confirmDialog).getByRole('button', { name: 'Відкликати доступ' }));
    await waitFor(async () => expect(within((await rowOf('М. В. Непийвода'))).getByText('Відкликано')).toBeInTheDocument());

    await user.click(within((await rowOf('М. В. Непийвода'))).getByRole('button', { name: /Дії/ }));
    expect(screen.queryByRole('menuitem', { name: 'Посилання для пароля' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('menuitem', { name: 'Повернути доступ' }));
    await waitFor(async () => expect(within((await rowOf('М. В. Непийвода'))).getByText('Активний')).toBeInTheDocument());
  });

  it('відмова сервера (LAST_ADMIN) не змінює стан рядка', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(apiPath('/admin/access/:id/revoke'), () =>
        problem({ status: 409, code: 'LAST_ADMIN', detail: 'Має залишитися хоча б один адміністратор, який може увійти.' }),
      ),
    );
    renderPage();
    const row = await rowOf('prokopchuk@vtc.vn.ua');
    await user.click(within(row).getByRole('button', { name: /Дії/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Відкликати доступ' }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Відкликати доступ' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(within((await rowOf('prokopchuk@vtc.vn.ua'))).getByText('Активний')).toBeInTheDocument();
  });

  it('свого доступу відкликати не можна: пункту немає', async () => {
    const user = userEvent.setup();
    renderPage();
    // у моках /api/me — демо-викладач krentsin
    const row = await rowOf('М. Д. Кренцін');
    await user.click(within(row).getByRole('button', { name: /Дії/ }));
    expect(await screen.findByRole('menuitem', { name: 'Змінити доступ' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'Відкликати доступ' })).not.toBeInTheDocument();
  });

  it('посилання для пароля показується один раз з терміном дії', async () => {
    const user = userEvent.setup();
    renderPage();
    const row = await rowOf('М. Д. Кренцін');
    await user.click(within(row).getByRole('button', { name: /Дії/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Посилання для пароля' }));
    const dialog = await screen.findByRole('dialog', { name: 'Посилання для пароля' });
    const url = await within(dialog).findByRole<HTMLInputElement>('textbox', { name: 'Посилання для пароля' });
    await waitFor(() => expect(url.value).toMatch(/\/password\/demo-password-\d+$/));
    expect(dialog).toHaveTextContent('для логіна krentsin');
    expect(dialog).toHaveTextContent(/Діє до/);
    // «Копіювати» — поруч із полем, а не поверх нього: довге посилання обрізається й не ховає кнопку
    const copy = within(dialog).getByRole('button', { name: 'Копіювати' });
    expect(url.contains(copy)).toBe(false);
    expect(url.parentElement?.contains(copy)).toBe(true);
    expect(url.className).toMatch(/text-ellipsis/);
    expect(url.className).toMatch(/min-w-0/);
    expect(copy.className).toMatch(/shrink-0/);
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    await user.click(copy);
    expect(writeText).toHaveBeenCalledWith(url.value);
    expect(await within(dialog).findByRole('button', { name: 'Скопійовано' })).toBeInTheDocument();
  });

  it('фільтр без результатів пропонує показати всіх', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: 'Хто може увійти' });
    await user.type(screen.getByRole('textbox', { name: /Пошук/ }), 'немає-такої-людини');
    expect(await screen.findByText('За цим фільтром нікого немає.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Показати всіх' }));
    expect(await screen.findByText('Диспетчер Розкладу')).toBeInTheDocument();
  });
});
