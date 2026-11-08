/**
 * Поведінка клієнта на справжніх формах відповідей бекенду, яких демо-мок сам не дає: null-поля без
 * семестру, 422 `teacher-not-linked`, 409 `stale-data`, коди правил відсутностей, дати зачеплених пар
 * без поля `date`, подія `absence.updated`.
 */
import { QueryClient } from '@tanstack/react-query';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, errorFromResponse, STALE_DATA_MESSAGE } from '@/api/errors';
import { invalidateFor } from '@/api/live';
import { qk } from '@/api/queryKeys';
import type { ChangeDto, MyBindingDto, PublicGroupDto, PublicMetaDto } from '@/api/types';
import { DecisionCard } from '@/components/admin-daily/changes/DecisionCard';
import { BindingCodeDialog } from '@/components/admin-daily/telegram/BindingCodeDialog';
import { NowPanel } from '@/components/public/home/NowPanel';
import { TelegramConnect } from '@/components/teacher/TelegramConnect';
import { ParityBadge } from '@/components/ui';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath, businessRule, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';

function problemResponse(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } });
}

describe('ProblemDetail: коди правил і застарілі дані', () => {
  it('422 з кодом відсутності показує українське повідомлення для цього правила', async () => {
    const error = await errorFromResponse(
      problemResponse(422, { type: 'urn:rozklad:problem:business-rule', code: 'ABSENCE_STARTED', detail: 'Server text' }),
    );
    expect(error.code).toBe('ABSENCE_STARTED');
    expect(error.message).toBe('Скасувати можна лише відсутність, яка ще не почалася. Якщо плани змінилися, повідомте диспетчера.');
    for (const code of ['ABSENCE_IN_PAST', 'ABSENCE_TEACHER_FIXED', 'ABSENCE_CANCELLED', 'ABSENCE_MISMATCH', 'teacher-not-linked']) {
      const mapped = await errorFromResponse(problemResponse(422, { code, detail: 'x' }));
      expect(mapped.message, code).not.toBe('x');
    }
  });

  it('невідомий код лишає текст сервера; 409 stale-data розпізнається окремо від перетину відсутностей', async () => {
    const unknown = await errorFromResponse(problemResponse(422, { code: 'SOMETHING_NEW', detail: 'Текст сервера' }));
    expect(unknown.message).toBe('Текст сервера');
    const stale = await errorFromResponse(problemResponse(409, { type: 'urn:rozklad:problem:stale-data', detail: 'Дані змінилися' }));
    expect(stale.isStaleData).toBe(true);
    const overlap = await errorFromResponse(problemResponse(409, { type: 'urn:rozklad:problem:conflict', detail: 'Перетин' }));
    expect(overlap.isStaleData).toBe(false);
    expect(overlap.message).toBe('Перетин');
  });
});

describe('SSE absence.updated', () => {
  it('оновлює відсутності, панель і кабінет', () => {
    const queryClient = new QueryClient();
    const spy = vi.spyOn(queryClient, 'invalidateQueries');
    const teacher = { id: 't', shortName: 'Т', displayName: 'Т' };
    invalidateFor(queryClient, {
      type: 'absence.updated',
      data: { id: 'a', teacher, dateFrom: '2026-11-09', dateTo: '2026-11-09', pairs: null, source: 'ADMIN', status: 'CANCELLED' },
    });
    const keys = spy.mock.calls.map(([filters]) => filters?.queryKey);
    expect(keys).toEqual(expect.arrayContaining([qk.admin.absences.all, ['admin', 'dashboard'], qk.me.all]));
  });
});

describe('null-поля публічних DTO', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2027-01-20T09:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('між семестрами «Зараз» каже про це прямо: без парності, номера тижня й тексту «null»', async () => {
    const meta: PublicMetaDto = {
      collegeName: 'ВТФК',
      semester: null,
      today: '2027-01-20',
      weekNumber: null,
      parity: null,
      studyDay: false,
      currentPair: null,
      nextPair: null,
      bellProfile: null,
    };
    server.use(http.get(apiPath('/public/meta'), () => HttpResponse.json(meta)));
    const { container } = renderWithProviders(<NowPanel />);
    expect(await screen.findByText('Навчального семестру зараз немає — пар не заплановано.')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/null|undefined|NaN/);
  });

  it('ParityBadge без парності нічого не малює', () => {
    const { container } = renderWithProviders(<ParityBadge parity={null} weekNumber={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('група без відділення в списку кодів привʼязки — без «null»', async () => {
    const group: PublicGroupDto = { id: 'g-1', code: '9ХХ1', course: 2, specialty: { letters: 'ХХ', name: 'Тест' }, department: null, shift: 1 };
    server.use(http.get(apiPath('/public/groups'), () => HttpResponse.json([group])));
    const user = userEvent.setup();
    renderWithProviders(<BindingCodeDialog open onOpenChange={() => undefined} />);
    await user.click(await screen.findByRole('combobox'));
    const option = await screen.findByRole('option', { name: /9ХХ1/ });
    expect(option.textContent).toContain('2 курс');
    expect(option.textContent).not.toContain('null');
  });
});

describe('Telegram у кабінеті: стан власної привʼязки', () => {
  const notLinked = () =>
    problem({ status: 422, slug: 'business-rule', code: 'teacher-not-linked', detail: 'Ваш обліковий запис не повʼязаний з викладачем.' });
  const status = (bound: boolean): MyBindingDto => ({
    teacherId: teacherNamed('Кренцін').id,
    teacherName: 'Кренцін',
    bound,
    binding: bound
      ? {
          id: 'b-1',
          type: 'TEACHER',
          chatId: '•••7890',
          title: 'Микола Кренцін',
          groupId: null,
          groupCode: null,
          teacherId: teacherNamed('Кренцін').id,
          digestEnabled: false,
          createdAt: '2026-09-02T07:15:00Z',
        }
      : null,
    botUsername: 'scheduler_vtfk_bot',
    botLink: 'https://t.me/scheduler_vtfk_bot',
  });

  it('422 teacher-not-linked показує пояснення в картці замість посилання', async () => {
    server.use(http.get(apiPath('/notify/bindings/me'), notLinked), http.post(apiPath('/notify/bindings/codes'), notLinked));
    renderWithProviders(<TelegramConnect teacherId={teacherNamed('Кренцін').id} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Ваш обліковий запис ще не повʼязаний з викладачем. Зверніться до диспетчера.');
    expect(screen.queryByRole('button', { name: 'Підключити Telegram' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Відкрити Telegram/ })).not.toBeInTheDocument();
  });

  it('уже підключений бот — стан замість нового посилання', async () => {
    server.use(http.get(apiPath('/notify/bindings/me'), () => HttpResponse.json(status(true))));
    renderWithProviders(<TelegramConnect teacherId={teacherNamed('Кренцін').id} />);
    expect(await screen.findByRole('status')).toHaveTextContent(/Telegram підключено: Микола Кренцін, з 02\.09\.2026/);
    expect(screen.queryByRole('button', { name: 'Підключити Telegram' })).not.toBeInTheDocument();
  });

  it('не підключений — посилання з QR-кодом', async () => {
    server.use(http.get(apiPath('/notify/bindings/me'), () => HttpResponse.json(status(false))));
    const user = userEvent.setup();
    renderWithProviders(<TelegramConnect teacherId={teacherNamed('Кренцін').id} />);
    await user.click(await screen.findByRole('button', { name: 'Підключити Telegram' }));
    expect(await screen.findByRole('link', { name: /Відкрити Telegram/ })).toBeInTheDocument();
  });
});

describe('рішення щодо пари: зміна причини з version', () => {
  const change: ChangeDto = {
    id: 'c-1',
    kind: 'REPLACE_TEACHER',
    status: 'CONFIRMED',
    date: '2026-11-09',
    pairFrom: 2,
    pairTo: 2,
    group: { id: 'g', code: '1КІ2', course: 1 },
    subgroup: 0,
    original: { discipline: { id: 'd', shortName: 'Ін.мова', name: 'Іноземна мова', color: null }, teacher: { id: 't1', shortName: 'Крисак Л.', displayName: 'Крисак Л.' }, room: null },
    new: { discipline: null, teacher: { id: 't2', shortName: 'Прокопчук', displayName: 'Прокопчук' }, room: { id: 'r', name: '110', type: 'CLASSROOM' } },
    reasonCode: 'ABSENT',
    reasonText: null,
    absenceId: null,
    confirmDeadline: null,
    respondedAt: null,
    orderId: null,
    history: [],
    approvalMode: 'AUTO',
    originalLessonId: 'l-1',
    swapLessonId: null,
    swapFromPair: null,
    printedReason: 'Відп.',
    responseComment: null,
    remindedAt: null,
    createdBy: 'admin',
    createdAt: '2026-11-09T05:00:00Z',
    version: 3,
  };

  it('надсилає version і поточну аудиторію; на 409 stale-data пояснює й лишає форму відкритою', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.put(apiPath('/admin/changes/:id'), async ({ request }) => {
        bodies.push(await request.json());
        return problem({ status: 409, slug: 'stale-data', title: 'Дані змінилися', detail: 'Запис щойно змінив інший користувач.' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <DecisionCard change={change} state="confirmed" busy={null} onResend={() => undefined} onConfirm={() => undefined} onCancel={() => undefined} />,
    );
    expect(screen.getByText('«Відп.»')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Змінити причину' }));
    const picker = screen.getByRole('radiogroup', { name: 'Причина в розпорядженні' });
    await user.click(within(picker).getByRole('radio', { name: /Зв\.зам\./ }));
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(STALE_DATA_MESSAGE));
    expect(bodies).toEqual([{ newRoomId: 'r', reasonCode: 'MUTUAL', reasonText: null, version: 3 }]);
    expect(screen.getByRole('button', { name: 'Зберегти' })).toBeInTheDocument();
  });

  it('замінник без Telegram: не «чекаємо відповіді», а «запит не надіслано» і підтвердження за викладача', async () => {
    const pending: ChangeDto = { ...change, status: 'PENDING_CONFIRMATION', confirmDeadline: '2099-01-01T00:00:00Z' };
    renderWithProviders(
      <DecisionCard change={pending} state="pending" busy={null} onResend={() => undefined} onConfirm={() => undefined} onCancel={() => undefined} />,
    );
    expect(await screen.findByText(/Прокопчук не підключив Telegram, тож запит не надіслано/)).toBeInTheDocument();
    expect(screen.queryByText(/Чекаємо відповіді в Telegram/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Нагадати в Telegram' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Підтвердити за викладача' })).toBeInTheDocument();
  });

  it('замінник з Telegram: чекаємо відповіді й можна нагадати', async () => {
    const krentsin = teacherNamed('Кренцін');
    const pending: ChangeDto = {
      ...change,
      status: 'PENDING_CONFIRMATION',
      confirmDeadline: '2099-01-01T00:00:00Z',
      new: { ...change.new, teacher: { id: krentsin.id, shortName: krentsin.shortName, displayName: krentsin.displayName } },
    };
    renderWithProviders(
      <DecisionCard change={pending} state="pending" busy={null} onResend={() => undefined} onConfirm={() => undefined} onCancel={() => undefined} />,
    );
    expect(await screen.findByText(/Чекаємо відповіді в Telegram/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Нагадати в Telegram' })).toBeInTheDocument();
  });

  it('відхилену заміну пропонує спитати ще раз, а не «підтвердити» (core: 422 CHANGE_DECLINED)', () => {
    const onResend = vi.fn();
    renderWithProviders(
      <DecisionCard change={{ ...change, status: 'DECLINED' }} state="declined" busy={null} onResend={onResend} onConfirm={() => undefined} onCancel={() => undefined} />,
    );
    expect(screen.queryByRole('button', { name: /підтвердити/i })).not.toBeInTheDocument();
    screen.getByRole('button', { name: 'Надіслати запит ще раз' }).click();
    expect(onResend).toHaveBeenCalledOnce();
  });
});

describe('кабінет: скасування відсутності', () => {
  it('ABSENCE_STARTED від core показується тим самим українським текстом', async () => {
    server.use(http.delete(apiPath('/me/absences/:id'), () => businessRule('ABSENCE_STARTED', 'server')));
    const { api } = await import('@/api/client');
    const error = await api.delete('/me/absences/x').catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('Скасувати можна лише відсутність, яка ще не почалася. Якщо плани змінилися, повідомте диспетчера.');
  });
});
