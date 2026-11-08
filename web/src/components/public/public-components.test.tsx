import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { Route, Routes, useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { useFavoritesStore } from '@/stores/favorites';
import { ScheduleFinder } from './home/ScheduleFinder';
import { InviteForm } from './invite/InviteForm';

function Location() {
  const location = useLocation();
  return <p data-testid="location">{decodeURIComponent(location.pathname)}</p>;
}

function renderFinder() {
  return renderWithProviders(
    <Routes>
      <Route path="*" element={<><ScheduleFinder /><Location /></>} />
    </Routes>,
  );
}

describe('ScheduleFinder', () => {
  it('«4ki1» знаходить групу, Enter відкриває її розклад', async () => {
    const user = userEvent.setup();
    renderFinder();
    const input = screen.getByRole('combobox', { name: /Знайти розклад/ });
    await user.type(input, '4ki1');
    const option = await screen.findByRole('option', { name: /4КІ1/ });
    expect(option).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', option.id);
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('location')).toHaveTextContent('/g/4КІ1');
  });

  it('стрілки ходять по результатах, Esc очищає поле', async () => {
    const user = userEvent.setup();
    renderFinder();
    const input = screen.getByRole('combobox', { name: /Знайти розклад/ });
    await user.type(input, 'кренц');
    const options = await screen.findAllByRole('option');
    expect(options[0]).toHaveTextContent('Кренцін');
    await user.keyboard('{ArrowUp}');
    expect(screen.getAllByRole('option').at(-1)).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Escape}');
    expect(input).toHaveValue('');
  });

  it('без збігів пояснює, що спробувати; поки поле порожнє — обране', async () => {
    useFavoritesStore.setState({ favorites: [{ type: 'GROUP', id: 'x', label: '2КІ1', url: '/g/2КІ1' }], recent: [] });
    const user = userEvent.setup();
    renderFinder();
    expect(screen.getByRole('link', { name: '2КІ1' })).toBeInTheDocument();
    await user.type(screen.getByRole('combobox', { name: /Знайти розклад/ }), 'жжжжж');
    expect(await screen.findByText(/Нічого не знайшли за «жжжжж»/)).toBeInTheDocument();
  });
});

describe('InviteForm', () => {
  it('перевіряє поля на клієнті й не надсилає некоректне', async () => {
    const user = userEvent.setup();
    renderWithProviders(<InviteForm token="demo" onAccepted={() => {}} onGone={() => {}} />);
    await user.type(screen.getByLabelText(/^Логін/), 'Ab');
    await user.type(screen.getByLabelText(/^Пароль/), 'password');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'password1');
    await user.click(screen.getByRole('button', { name: 'Створити обліковий запис' }));
    expect(await screen.findByText('Щонайменше 3 символи')).toBeInTheDocument();
    expect(screen.getByText('Додайте хоча б одну цифру')).toBeInTheDocument();
    expect(screen.getByText('Паролі не збігаються')).toBeInTheDocument();
  });

  it('зайнятий логін (409) показує під полем і лишає введене', async () => {
    const user = userEvent.setup();
    renderWithProviders(<InviteForm token="demo" onAccepted={() => {}} onGone={() => {}} />);
    await user.type(screen.getByLabelText(/^Логін/), 'krentsin');
    await user.type(screen.getByLabelText(/^Пароль/), 'secret-123');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'secret-123');
    await user.click(screen.getByRole('button', { name: 'Створити обліковий запис' }));
    expect(await screen.findByText('Цей логін уже зайнятий — оберіть інший')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Логін/)).toHaveValue('krentsin');
  });

  it('успіх і прострочене запрошення (410) передає наверх', async () => {
    const user = userEvent.setup();
    const accepted: string[] = [];
    const gone: string[] = [];
    const { unmount } = renderWithProviders(
      <InviteForm token="demo" onAccepted={(u) => accepted.push(u)} onGone={(m) => gone.push(m)} />,
    );
    await user.type(screen.getByLabelText(/^Логін/), 'kolesnyk');
    await user.type(screen.getByLabelText(/^Пароль/), 'secret-123');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'secret-123');
    await user.click(screen.getByRole('button', { name: 'Створити обліковий запис' }));
    await waitFor(() => expect(accepted).toEqual(['kolesnyk']));
    unmount();

    server.use(http.post(apiPath('/public/invites/:token/accept'), () => problem({ status: 410, detail: 'Термін дії запрошення минув.' })));
    renderWithProviders(<InviteForm token="demo" onAccepted={(u) => accepted.push(u)} onGone={(m) => gone.push(m)} />);
    await user.type(screen.getByLabelText(/^Логін/), 'kolesnyk2');
    await user.type(screen.getByLabelText(/^Пароль/), 'secret-123');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'secret-123');
    await user.click(screen.getByRole('button', { name: 'Створити обліковий запис' }));
    await waitFor(() => expect(gone).toEqual(['Термін дії запрошення минув.']));
  });
});
