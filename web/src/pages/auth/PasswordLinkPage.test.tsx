import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { AccessDto, PasswordLinkDto } from '@/api/access';
import { api } from '@/api/client';
import { renderWithProviders } from '@/test/render';
import PasswordLinkPage from './PasswordLinkPage';

async function linkFor(email: string | null, username?: string): Promise<string> {
  const accounts = await api.get<AccessDto[]>('/admin/access');
  let account = accounts.find((a) => (username ? a.username === username : a.email === email));
  account ??= await api.post<AccessDto>('/admin/access', { email, roles: ['ADMIN'], displayName: 'Директор' });
  const link = await api.post<PasswordLinkDto>(`/admin/access/${account.id}/password-link`);
  return link.url.slice(link.url.lastIndexOf('/') + 1);
}

function renderAt(token: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/password/:token" element={<PasswordLinkPage />} />
    </Routes>,
    { route: `/password/${token}` },
  );
}

describe('Новий пароль за посиланням', () => {
  it('наявний логін показано без змін; пароль від 12 символів', async () => {
    const user = userEvent.setup();
    renderAt(await linkFor(null, 'krentsin'));
    expect(await screen.findByText(/М\. Д\. Кренцін/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Логін/)).toHaveValue('krentsin');
    expect(screen.getByLabelText(/^Логін/)).toHaveAttribute('readonly');
    expect(screen.getByText('щонайменше 12 символів')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^Пароль/), 'short-1');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'short-1');
    await user.click(screen.getByRole('button', { name: 'Зберегти пароль' }));
    expect(await screen.findByText('Щонайменше 12 символів')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/^Пароль/));
    await user.clear(screen.getByLabelText(/Повторіть пароль/));
    await user.type(screen.getByLabelText(/^Пароль/), 'Long-enough-pass-1');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'Long-enough-pass-1');
    await user.click(screen.getByRole('button', { name: 'Зберегти пароль' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Ваш логін — krentsin');
  });

  it('доступ за e-mail без логіна: людина вибирає логін тут', async () => {
    const user = userEvent.setup();
    renderAt(await linkFor('boss@vtc.vn.ua'));
    const login = await screen.findByLabelText(/^Логін/);
    expect(login).not.toHaveAttribute('readonly');
    await user.type(login, 'boss');
    await user.type(screen.getByLabelText(/^Пароль/), 'Long-enough-pass-1');
    await user.type(screen.getByLabelText(/Повторіть пароль/), 'Long-enough-pass-1');
    await user.click(screen.getByRole('button', { name: 'Зберегти пароль' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Ваш логін — boss');
  });

  it('замінене новим посилання — недійсне з поясненням', async () => {
    const older = await linkFor(null, 'nepyivoda');
    await linkFor(null, 'nepyivoda');
    renderAt(older);
    expect(await screen.findByText(/посилання замінили новим/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Увійти' })).toBeInTheDocument();
  });

  it('невідомий токен', async () => {
    renderAt('no-such-token');
    expect(await screen.findByText(/Посилання неповне або застаріле/)).toBeInTheDocument();
  });
});
