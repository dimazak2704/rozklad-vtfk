import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { EmailPreferencesDto, EmailPreferencesRequest } from '@/api/types';
import { notify } from '@/components/ui';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import NotificationsPage from '@/pages/admin/NotificationsPage';
import { renderWithProviders } from '@/test/render';
import { EmailPreferences } from './EmailPreferences';
import { addressNote } from './labels';

const WAIT = { timeout: 10_000 };

afterEach(() => vi.restoreAllMocks());

/** Підміняє GET /notify/email/me: три види листів і потрібні поля поверх підтвердженої адреси. */
function withPrefs(patch: Partial<EmailPreferencesDto>) {
  server.use(
    http.get(apiPath('/notify/email/me'), () => {
      const base: EmailPreferencesDto = {
        channel: { enabled: true, reason: null },
        address: 'krentsin@vtc.vn.ua',
        addressStatus: 'VERIFIED',
        verifiedBy: 'GOOGLE',
        enabled: true,
        kinds: [
          { kind: 'SUBSTITUTION_NOTICE', audience: 'TEACHER', enabled: true, defaultEnabled: true, switchable: true },
          { kind: 'SECURITY_TELEGRAM_RELINKED', audience: 'TEACHER', enabled: true, defaultEnabled: true, switchable: false },
          { kind: 'GENERATION_FINISHED', audience: 'ADMIN', enabled: true, defaultEnabled: true, switchable: true },
        ],
        version: 3,
      };
      return HttpResponse.json({ ...base, ...patch });
    }),
  );
}

/** Записує тіла PUT і відповідає тим, що надіслали (як сервер). */
function recordPuts() {
  const bodies: EmailPreferencesRequest[] = [];
  server.use(
    http.put(apiPath('/notify/email/me'), async ({ request }) => {
      const body = (await request.json()) as EmailPreferencesRequest;
      bodies.push(body);
      return HttpResponse.json({
        channel: { enabled: true, reason: null },
        address: 'krentsin@vtc.vn.ua',
        addressStatus: 'VERIFIED',
        verifiedBy: 'GOOGLE',
        enabled: body.enabled,
        kinds: [
          { kind: 'SUBSTITUTION_NOTICE', audience: 'TEACHER', enabled: body.kinds.SUBSTITUTION_NOTICE ?? true, defaultEnabled: true, switchable: true },
          { kind: 'SECURITY_TELEGRAM_RELINKED', audience: 'TEACHER', enabled: true, defaultEnabled: true, switchable: false },
          { kind: 'GENERATION_FINISHED', audience: 'ADMIN', enabled: body.kinds.GENERATION_FINISHED ?? true, defaultEnabled: true, switchable: true },
        ],
        version: (body.version ?? 0) + 1,
      } satisfies EmailPreferencesDto);
    }),
  );
  return bodies;
}

describe('addressNote', () => {
  it('каже, чим підтверджено адресу, а коли листи не підуть — що зробити', () => {
    expect(addressNote({ addressStatus: 'VERIFIED', verifiedBy: 'GOOGLE' })).toEqual({ ok: true, text: 'Адресу підтверджено входом через Google.' });
    expect(addressNote({ addressStatus: 'VERIFIED', verifiedBy: 'TRUSTED_DOMAIN' }).text).toBe('Корпоративна адреса коледжу.');
    expect(addressNote({ addressStatus: 'UNVERIFIED' })).toMatchObject({ ok: false, text: expect.stringContaining('Увійдіть один раз через Google') });
    expect(addressNote({ addressStatus: 'MISSING' })).toMatchObject({ ok: false, text: expect.stringContaining('навчальну частину') });
  });
});

describe('EmailPreferences', () => {
  it('кабінет: адреса лише для читання, перемикачі своїх замін з одним реченням, диспетчерських немає', async () => {
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    expect(await screen.findByText('krentsin@vtc.vn.ua', undefined, WAIT)).toBeInTheDocument();
    expect(screen.getByText('Адресу підтверджено входом через Google.')).toBeInTheDocument();
    expect(screen.getByText('Змінити адресу може лише навчальна частина.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    const notice = screen.getByRole('switch', { name: 'Нова заміна' });
    expect(notice).toBeChecked();
    expect(notice).toHaveAccessibleDescription(/Коли вам ставлять заміну/);
    // лист безпеки не вимикається: лише слово «Завжди», без перемикача
    expect(screen.queryByRole('switch', { name: 'Telegram підключено в іншому чаті' })).not.toBeInTheDocument();
    expect(screen.getByText('Telegram підключено в іншому чаті').closest('li')).toHaveTextContent('Завжди');
    expect(screen.getByRole('switch', { name: 'Ваші рядки з розпорядження' })).not.toBeChecked();
    expect(screen.queryByRole('switch', { name: 'Генерацію розкладу завершено' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Для диспетчера' })).not.toBeInTheDocument();
  });

  it('перемикач діє одразу: PUT несе стан усіх перемикачів і версію', async () => {
    withPrefs({});
    const bodies = recordPuts();
    const user = userEvent.setup();
    renderWithProviders(<EmailPreferences audiences={['ADMIN', 'TEACHER']} />);
    const generation = await screen.findByRole('switch', { name: 'Генерацію розкладу завершено' }, WAIT);
    await user.click(generation);
    expect(generation).not.toBeChecked();
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ enabled: true, kinds: { SUBSTITUTION_NOTICE: true, GENERATION_FINISHED: false }, version: 3 });

    // наступне натискання — з новою версією від сервера і з попередньою зміною в тілі
    await user.click(screen.getByRole('switch', { name: 'Нова заміна' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({ enabled: true, kinds: { SUBSTITUTION_NOTICE: false, GENERATION_FINISHED: false }, version: 4 });
  });

  it('загальний перемикач вимкнено — види недоступні, але показують збережений вибір', async () => {
    withPrefs({
      enabled: false,
      kinds: [
        { kind: 'SUBSTITUTION_NOTICE', audience: 'TEACHER', enabled: true, defaultEnabled: true, switchable: true },
        { kind: 'GENERATION_FINISHED', audience: 'ADMIN', enabled: false, defaultEnabled: true, switchable: true },
      ],
    });
    const bodies = recordPuts();
    const user = userEvent.setup();
    renderWithProviders(<EmailPreferences audiences={['ADMIN', 'TEACHER']} />);
    const master = await screen.findByRole('switch', { name: 'Надсилати листи на пошту' }, WAIT);
    expect(master).not.toBeChecked();
    const notice = screen.getByRole('switch', { name: 'Нова заміна' });
    expect(notice).toBeDisabled();
    expect(notice).toBeChecked();
    const generation = screen.getByRole('switch', { name: 'Генерацію розкладу завершено' });
    expect(generation).toBeDisabled();
    expect(generation).not.toBeChecked();
    expect(screen.getByText(/вибір за видами збережено/)).toBeInTheDocument();

    // увімкнули знову — повертається саме той вибір, а не «усе вимкнено»
    await user.click(master);
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ enabled: true, kinds: { SUBSTITUTION_NOTICE: true, GENERATION_FINISHED: false }, version: 3 });
  });

  it('сервер не зберіг — повідомлення й стан із сервера', async () => {
    withPrefs({});
    server.use(http.put(apiPath('/notify/email/me'), () => problem({ status: 503 })));
    const error = vi.spyOn(notify, 'error');
    const user = userEvent.setup();
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    const notice = await screen.findByRole('switch', { name: 'Нова заміна' }, WAIT);
    await user.click(notice);
    await waitFor(() => expect(error).toHaveBeenCalledWith(expect.anything(), 'Не вдалося зберегти налаштування листів'));
    await waitFor(() => expect(notice).toBeChecked());
  });

  it('немає адреси: що зробити, без тестового листа', async () => {
    withPrefs({ address: null, addressStatus: 'MISSING', verifiedBy: null });
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    expect(await screen.findByText('Адреси немає', undefined, WAIT)).toBeInTheDocument();
    expect(screen.getByText(/Попросіть навчальну частину додати вашу корпоративну адресу/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Надіслати тестовий лист' })).not.toBeInTheDocument();
    expect(screen.queryByText('Змінити адресу може лише навчальна частина.')).not.toBeInTheDocument();
  });

  it('адресу не підтверджено: пояснення про вхід через Google, без тестового листа', async () => {
    withPrefs({ address: 'krentsin@gmail.com', addressStatus: 'UNVERIFIED', verifiedBy: null });
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    expect(await screen.findByText(/Увійдіть один раз через Google/, undefined, WAIT)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Надіслати тестовий лист' })).not.toBeInTheDocument();
  });

  it('пошту на сервері не налаштовано: причина і перемикачі лише для читання', async () => {
    withPrefs({ channel: { enabled: false, reason: null } });
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    expect(await screen.findByText(/Сервер поки не надсилає листи: пошту на ньому не налаштовано/, undefined, WAIT)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Надсилати листи на пошту' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Нова заміна' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Надіслати тестовий лист' })).not.toBeInTheDocument();
  });

  it('обліковий запис без викладача: пояснення замість порожнього списку', async () => {
    withPrefs({ kinds: [] });
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} emptyNote="Листів про заміни немає." />);
    expect(await screen.findByText('Листів про заміни немає.', undefined, WAIT)).toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
  });

  it('тестовий лист: у черзі — повідомлення з адресою; забагато — зрозуміла причина', async () => {
    const success = vi.spyOn(notify, 'success');
    const error = vi.spyOn(notify, 'error');
    const user = userEvent.setup();
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    const button = await screen.findByRole('button', { name: 'Надіслати тестовий лист' }, WAIT);
    await user.click(button);
    await waitFor(() =>
      expect(success).toHaveBeenCalledWith('Тестовий лист у черзі', { description: expect.stringContaining('krentsin@vtc.vn.ua') }),
    );

    server.use(http.post(apiPath('/notify/email/me/test'), () => problem({ status: 429 })));
    await user.click(button);
    await waitFor(() => expect(error).toHaveBeenCalledWith(expect.stringContaining('не більше трьох на годину')));
  });

  it('налаштування не завантажено — помилка з повтором', async () => {
    server.use(http.get(apiPath('/notify/email/me'), () => problem({ status: 503 })));
    renderWithProviders(<EmailPreferences audiences={['TEACHER']} />);
    expect(await screen.findByText(/Налаштування листів не завантажено/, undefined, WAIT)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Спробувати ще раз/ })).toBeInTheDocument();
  });
});

describe('сторінка «Сповіщення» диспетчера', () => {
  it('стан пошти рядком, обидві групи листів, журнал із масками адрес', async () => {
    const user = userEvent.setup();
    renderWithProviders(<NotificationsPage />, { route: '/admin/notifications' });
    expect(await screen.findByText('Тестова пошта стенду', undefined, WAIT)).toBeInTheDocument();
    expect(screen.getByText('mailpit:1025')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Для диспетчера' }, WAIT)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Про мої заміни' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Журнал листів' }));
    const table = await screen.findByRole('table', { name: 'Журнал листів' }, WAIT);
    expect(within(table).getByText('Розклад згенеровано без конфліктів, штраф 35')).toBeInTheDocument();
    expect(within(table).getAllByText('k•••@vtc.vn.ua').length).toBeGreaterThan(0);
    expect(within(table).getByText('Замінено новішим')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Не надіслано' }));
    await waitFor(() => expect(within(table).queryByText('Замінено новішим')).not.toBeInTheDocument());
    expect(within(table).getByText(/535 5\.7\.8/)).toBeInTheDocument();
  });

  it('журнал фільтрується за видом листа', async () => {
    const user = userEvent.setup();
    renderWithProviders(<NotificationsPage />, { route: '/admin/notifications' });
    await user.click(await screen.findByRole('tab', { name: 'Журнал листів' }, WAIT));
    const table = await screen.findByRole('table', { name: 'Журнал листів' }, WAIT);
    expect(await within(table).findByText('Розклад згенеровано без конфліктів, штраф 35', undefined, WAIT)).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Вид листа' }));
    await user.click(await screen.findByRole('option', { name: 'Відмова від заміни' }));

    await waitFor(() => expect(within(table).queryByText('Розклад згенеровано без конфліктів, штраф 35')).not.toBeInTheDocument());
    expect(within(table).getByText('Відмова від заміни: Прокопчук, чт, 12 листопада, 2 пара, 2ОБ')).toBeInTheDocument();
  });

  it('пошту не налаштовано — так і сказано', async () => {
    server.use(
      http.get(apiPath('/notify/email/status'), () =>
        HttpResponse.json({ enabled: false, mode: 'OFF', host: null, port: null, from: null, replyTo: null, tls: null, problem: null }),
      ),
    );
    renderWithProviders(<NotificationsPage />, { route: '/admin/notifications' });
    expect(await screen.findByText('Листи не надсилаються', undefined, WAIT)).toBeInTheDocument();
    expect(screen.getByText(/MAIL_HOST порожній/)).toBeInTheDocument();
  });
});
