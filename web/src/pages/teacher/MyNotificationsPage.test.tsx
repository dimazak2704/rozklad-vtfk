import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeDto, MyBindingDto } from '@/api/types';
import { TelegramConnect } from '@/components/teacher/TelegramConnect';
import { notify } from '@/components/ui';
import { ConfirmDialogHost } from '@/components/ui/ConfirmDialog';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import MyNotificationsPage from './MyNotificationsPage';

const WAIT = { timeout: 10_000 };
const ACCOUNT_LINE = 'Telegram привʼязано: Микола (@krentsin)';

function renderPage() {
  return renderWithProviders(
    <>
      <MyNotificationsPage />
      <ConfirmDialogHost />
    </>,
  );
}

beforeEach(() => resetMockStores());
afterEach(() => vi.restoreAllMocks());

describe('сторінка «Сповіщення»', () => {
  it('дві частини: пошта й Telegram; видно, чий Telegram привʼязано', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Пошта' })).toBeInTheDocument();
    const telegram = screen.getByRole('complementary', { name: 'Telegram' });
    expect(await within(telegram).findByText(ACCOUNT_LINE, {}, WAIT)).toBeInTheDocument();
    expect(within(telegram).getByText(/^Чат/)).toHaveTextContent(/^Чат •••\d{4}$/);
    expect(within(telegram).getByRole('button', { name: 'Відвʼязати' })).toBeInTheDocument();
  });

  it('«Відвʼязати» з підтвердженням: привʼязку знято, картка знову пропонує підключити бота', async () => {
    const success = vi.spyOn(notify, 'success');
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Відвʼязати' }, WAIT));

    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('Бот більше не надсилатиме вам запити на заміни в Telegram. Листи на пошту лишаться.');
    await user.click(within(dialog).getByRole('button', { name: 'Відвʼязати' }));

    expect(await screen.findByRole('button', { name: 'Підключити Telegram' }, WAIT)).toBeInTheDocument();
    expect(screen.queryByText(/Telegram привʼязано/)).not.toBeInTheDocument();
    expect(success).toHaveBeenCalledWith('Telegram відвʼязано', expect.anything());
    // і в моку notification привʼязки вже немає: повторний запит стану її не повертає
    const status = (await fetch(apiPath('/notify/bindings/me')).then((response) => response.json())) as MyBindingDto;
    expect(status.bound).toBe(false);
  });

  it('«Залишити» в діалозі нічого не змінює', async () => {
    const unlinked = vi.fn();
    server.use(
      http.delete(apiPath('/notify/bindings/me'), () => {
        unlinked();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Відвʼязати' }, WAIT));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Залишити' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(unlinked).not.toHaveBeenCalled();
    expect(screen.getByText(ACCOUNT_LINE)).toBeInTheDocument();
  });

  it('збій відвʼязки — повідомлення, привʼязка лишається на екрані', async () => {
    const failed = vi.spyOn(notify, 'error');
    server.use(http.delete(apiPath('/notify/bindings/me'), () => problem({ status: 503, slug: 'unavailable', detail: 'Сервіс недоступний.' })));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Відвʼязати' }, WAIT));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Відвʼязати' }));
    await waitFor(() => expect(failed).toHaveBeenCalledWith(expect.anything(), 'Не вдалося відвʼязати Telegram'));
    expect(screen.getByText(ACCOUNT_LINE)).toBeInTheDocument();
  });

  it('стара привʼязка без акаунта — показуємо чат', async () => {
    const krentsin = teacherNamed('Кренцін');
    const status: MyBindingDto = {
      teacherId: krentsin.id,
      teacherName: 'Кренцін М.Д.',
      bound: true,
      binding: {
        id: 'b-1',
        type: 'TEACHER',
        chatId: '•••7890',
        title: 'Кренцін М.Д.',
        groupId: null,
        groupCode: null,
        teacherId: krentsin.id,
        digestEnabled: false,
        createdAt: '2026-09-02T07:15:00Z',
        telegramAccount: null,
      },
      botUsername: 'scheduler_vtfk_bot',
      botLink: 'https://t.me/scheduler_vtfk_bot',
    };
    server.use(http.get(apiPath('/notify/bindings/me'), () => HttpResponse.json(status)));
    renderPage();
    expect(await screen.findByText('Telegram привʼязано: чат •••7890', {}, WAIT)).toBeInTheDocument();
    expect(screen.queryByText(/^Чат/)).not.toBeInTheDocument();
  });

  it('обліковий запис без викладача — пояснення замість Telegram', async () => {
    const me: MeDto = { userId: 'u-1', username: 'dispatcher', roles: ['ADMIN'], teacher: null };
    server.use(http.get(apiPath('/me'), () => HttpResponse.json(me)));
    renderPage();
    const telegram = screen.getByRole('complementary', { name: 'Telegram' });
    expect(await within(telegram).findByText(/Ваш обліковий запис з викладачем не повʼязаний/, {}, WAIT)).toBeInTheDocument();
    expect(within(telegram).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('Telegram на «Мій розклад»', () => {
  it('без allowUnlink — лише стан, без «Відвʼязати»', async () => {
    renderWithProviders(<TelegramConnect teacherId={teacherNamed('Кренцін').id} />);
    expect(await screen.findByRole('status', {}, WAIT)).toHaveTextContent(/Telegram підключено/);
    expect(screen.queryByRole('button', { name: 'Відвʼязати' })).not.toBeInTheDocument();
  });
});
