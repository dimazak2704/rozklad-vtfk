import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BellSlotDto, ChangeDto, DayDto, LessonView, PreferenceKind, Weekday } from '@/api/types';
import { notify } from '@/components/ui';
import { teacherNamed } from '@/mocks/fixtures';
import { changesForTeacher } from '@/mocks/handlers/public';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { absenceSchema, toAbsenceRequest } from './absence-schema';
import { AbsenceForm } from './AbsenceForm';
import { AbsenceList } from './AbsenceList';
import { awaitingAnswer, changePassed, changeReplaced, changeStatusBadge, changeWhen, deadlineDay, isUnansweredNotice, mostUrgent, REPLY_UNDO_MS } from './change-text';
import {
  assignmentLessons,
  assignmentPairs,
  assignmentPhrase,
  confirmedAssignments,
  endSentence,
  lessonPhrase,
  nextDaySentence,
  nextRequest,
  withAssignments,
  withRequest,
} from './my-day';
import { MyNow } from './MyNow';
import { MyUpcomingChanges } from './MyUpcomingChanges';
import { PreferenceGrid, PreferenceLegend } from './PreferenceGrid';
import { cellKey, changedCells, nextKind, shorterDaysNote, templateCells, toList, toMap, type CellKey } from './preferences';
import { SubstitutionRequests } from './SubstitutionRequests';
import { useAssignedSchedule } from './useAssignedSchedule';
import { answered } from '@/api/me';

const krentsin = teacherNamed('Кренцін');

describe('SubstitutionRequests', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function trackReplies() {
    const sent: string[] = [];
    server.use(
      http.post(apiPath('/me/changes/:id/:decision'), ({ params }) => {
        sent.push(`${String(params.decision)}:${String(params.id)}`);
        return problem({ status: 409, detail: 'На цей запит уже відповіли.' });
      }),
    );
    return sent;
  }

  it('підтвердження: картка зникає одразу, а запит іде лише після 8 с, які дає «Скасувати»', async () => {
    const sent = trackReplies();
    const undo = vi.spyOn(notify, 'undo');
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const requests = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    expect(requests).toHaveLength(2);
    await user.click(within(requests[0] as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(1);
    // один рядок без опису (DESIGN §11.6): що зроблено і з якою парою
    expect(undo).toHaveBeenCalledWith(expect.stringMatching(/^Підтверджено: \S+, .+ \d\u00a0пара$/), expect.objectContaining({ duration: REPLY_UNDO_MS }));
    expect(undo.mock.calls[0]?.[1]).not.toHaveProperty('description');
    expect(sent).toEqual([]);

    act(() => undo.mock.calls[0]?.[1].onUndo());
    expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(2);
    expect(sent).toEqual([]);
  });

  it('якщо сервер відмовив — картка повертається', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
    const sent = trackReplies();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const requests = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    await user.click(within(requests[0] as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REPLY_UNDO_MS);
    });
    await waitFor(() => expect(sent).toHaveLength(1));
    await waitFor(() => expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(2));
  });

  it('відповідь пішла за таймером — «Скасувати» зникає разом із нею, а пізній клік каже, що вже надіслано', async () => {
    // Sonner ставить відлік сповіщення на паузу, поки над ним курсор, тож воно могло б пережити наш таймер
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
    const sent: string[] = [];
    const onRequest = ({ request }: { request: Request }) => {
      if (request.method === 'POST') sent.push(new URL(request.url).pathname);
    };
    server.events.on('request:start', onRequest);
    const undo = vi.spyOn(notify, 'undo');
    const dismiss = vi.spyOn(notify, 'dismiss');
    const info = vi.spyOn(notify, 'info');
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
      const [request] = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
      await user.click(within(request as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
      expect(dismiss).not.toHaveBeenCalled();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(REPLY_UNDO_MS);
      });
      await waitFor(() => expect(sent).toEqual([expect.stringMatching(/\/me\/changes\/[^/]+\/confirm$/)]));
      expect(dismiss).toHaveBeenCalledWith(undo.mock.results[0]?.value);

      act(() => undo.mock.calls[0]?.[1].onUndo());
      expect(info).toHaveBeenCalledWith('Відповідь уже надіслано, скасувати не вийде');
      await waitFor(() => expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(1));
      expect(sent).toHaveLength(1);
    } finally {
      server.events.removeListener('request:start', onRequest);
    }
  });

  it('вихід зі сторінки закриває й сповіщення: «Скасувати» не лишається після надісланої відповіді', async () => {
    trackReplies();
    const undo = vi.spyOn(notify, 'undo');
    const dismiss = vi.spyOn(notify, 'dismiss');
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [request] = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    await user.click(within(request as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    unmount();
    expect(dismiss).toHaveBeenCalledWith(undo.mock.results[0]?.value);
  });

  it('піти зі сторінки, не скасувавши, — відповідь іде одразу', async () => {
    const sent = trackReplies();
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [notice] = await screen.findAllByRole('article', { name: /^Нова заміна/ });
    await user.click(within(notice as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    expect(sent).toEqual([]);
    unmount();
    await waitFor(() => expect(sent).toEqual([expect.stringMatching(/^confirm:/)]));
  });

  it.each([
    ['закрили вкладку чи застосунок (pagehide)', () => window.dispatchEvent(new Event('pagehide'))],
    [
      'згорнули вкладку (visibilitychange → hidden)',
      () => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
        document.dispatchEvent(new Event('visibilitychange'));
      },
    ],
  ])('%s — відкладена відповідь іде одразу, з keepalive, і лише один раз', async (_, leave) => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
    const sent: string[] = [];
    const keepalive: boolean[] = [];
    server.use(
      http.post(apiPath('/me/changes/:id/:decision'), ({ params, request }) => {
        sent.push(`${String(params.decision)}:${String(params.id)}`);
        keepalive.push(request.keepalive);
        return problem({ status: 409, detail: 'На цей запит уже відповіли.' });
      }),
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { unmount } = renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [request] = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    await user.click(within(request as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    expect(sent).toEqual([]);
    try {
      act(leave);
      await waitFor(() => expect(sent).toEqual([expect.stringMatching(/^confirm:/)]));
      expect(keepalive).toEqual([true]);
      // ні таймер «Скасувати», ні вихід зі сторінки не шлють її вдруге
      await act(async () => {
        await vi.advanceTimersByTimeAsync(REPLY_UNDO_MS * 2);
      });
      unmount();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(100);
      });
      expect(sent).toHaveLength(1);
    } finally {
      Reflect.deleteProperty(document, 'visibilityState');
    }
  });

  it('вкладка знову видима — нічого не шле: відповідь іде лише тоді, коли сторінка ховається', async () => {
    const sent = trackReplies();
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [request] = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    await user.click(within(request as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(document.visibilityState).toBe('visible');
    expect(sent).toEqual([]);
  });

  it('заміну, яку доповнення передало іншому, відповісти не просять: у «Моїх замінах» вона як передана', async () => {
    server.use(
      http.get(apiPath('/me/changes'), () =>
        Response.json(
          changesForTeacher(krentsin.id, new Date()).map((change) =>
            change.approvalMode === 'NOTIFY'
              ? { ...change, status: 'APPLIED', supersededBy: { changeId: '00000000-0000-4000-8000-0000000000c2', kind: 'REPLACE_TEACHER', orderId: null } }
              : change,
          ),
        ),
      ),
    );
    renderWithProviders(
      <>
        <SubstitutionRequests teacherId={krentsin.id} />
        <MyUpcomingChanges />
      </>,
    );
    expect(await screen.findByRole('heading', { name: /^2\sзаміни чекають вашої відповіді$/ })).toBeInTheDocument();
    expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(2);
    expect(screen.queryAllByRole('article', { name: /^Нова заміна/ })).toHaveLength(0);
    const mine = screen.getByRole('region', { name: 'Мої заміни' });
    expect(within(mine).getByText('Передано іншому доповненням')).toBeInTheDocument();
    expect(within(mine).queryByRole('button')).not.toBeInTheDocument();
  });

  it('відмова — звичайною кнопкою, з коментарем і можливістю передумати', async () => {
    const undo = vi.spyOn(notify, 'undo');
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [request] = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    await user.click(within(request as HTMLElement).getByRole('button', { name: 'Не можу' }));
    await user.type(await screen.findByLabelText(/Коментар для диспетчера/), 'Маю пару');
    await user.click(screen.getByRole('button', { name: 'Відмовитися' }));
    expect(undo).toHaveBeenCalledWith(expect.stringMatching(/^Ви відмовилися: /), expect.objectContaining({ duration: REPLY_UNDO_MS }));
    expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(1);
  });

  it('однакові кнопки в усіх рядках; заповнена — лише в найтерміновішому, і її пояснює строк першим рядком', async () => {
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const rows = await screen.findAllByRole('article');
    const confirms = rows.map((row) => within(row).getByRole('button', { name: 'Підтверджую' }));
    expect(confirms).toHaveLength(rows.length);
    const filled = rows.filter((_, index) => confirms[index]?.className.includes('bg-accent'));
    expect(filled).toHaveLength(1);
    const urgent = filled[0] as HTMLElement;
    expect(urgent).toHaveAccessibleName(/^Запит на заміну/);
    const first = within(urgent).getByText((_, element) => element?.tagName === 'P' && /^Відповісти до \d\d:\d\d/.test(element.textContent ?? ''));
    expect(first).toHaveClass('text-body');
  });

  it('найтерміновіший — прохання з найближчим відкритим строком, інакше жодного', () => {
    const now = new Date('2026-11-09T08:00:00Z');
    const list = [
      { id: 'notice', status: 'APPLIED', confirmDeadline: null },
      { id: 'late', status: 'PENDING_CONFIRMATION', confirmDeadline: '2026-11-12T05:30:00Z' },
      { id: 'soon', status: 'PENDING_CONFIRMATION', confirmDeadline: '2026-11-10T05:30:00Z' },
      { id: 'overdue', status: 'PENDING_CONFIRMATION', confirmDeadline: '2026-11-09T05:30:00Z' },
    ] as ChangeDto[];
    expect(mostUrgent(list, now)).toBe('soon');
    // без відкритих строків заповненої кнопки немає: пояснити її нічим
    expect(mostUrgent(list.slice(0, 1), now)).toBeNull();
    expect(mostUrgent([list[0], list[3]] as ChangeDto[], now)).toBeNull();
    expect(mostUrgent([], now)).toBeNull();
  });

  it('призначена заміна (NOTIFY): «Підтверджую» чи «Не можу», без строку відповіді', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [notice] = await screen.findAllByRole('article', { name: /^Нова заміна/ });
    const card = notice as HTMLElement;
    expect(within(card).queryByText(/відповісти до/)).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Не можу' })).toBeInTheDocument();
    await user.click(within(card).getByRole('button', { name: 'Підтверджую' }));
    await waitFor(() => expect(screen.queryAllByRole('article', { name: /^Нова заміна/ })).toHaveLength(0));
  });

  it('пара вже минула: лишається тільки «Підтверджую», бо відмовитися від неї нічого не дасть', async () => {
    // 10:05 за Києвом — перша пара 9 листопада вже скінчилася, друга ще попереду
    const notice = changesForTeacher(krentsin.id, new Date()).find((change) => change.approvalMode === 'NOTIFY');
    if (!notice) throw new Error('у фікстурах немає повідомлення NOTIFY');
    server.use(
      http.get(apiPath('/me/changes'), () =>
        Response.json([
          { ...notice, id: 'over', date: '2026-11-09', pairFrom: 1, pairTo: 1, status: 'APPLIED', teacherAnswer: null, supersededBy: null },
          { ...notice, id: 'ahead', date: '2026-11-09', pairFrom: 4, pairTo: 4, status: 'APPLIED', teacherAnswer: null, supersededBy: null },
        ]),
      ),
    );
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const over = await screen.findByRole('article', { name: /1 пара$/ });
    await waitFor(() => expect(within(over).getByText('Вам поставили заміну · пара вже минула')).toBeInTheDocument());
    expect(within(over).queryByRole('button', { name: 'Не можу' })).not.toBeInTheDocument();
    expect(within(over).getByRole('button', { name: 'Підтверджую' })).toBeInTheDocument();
    const ahead = screen.getByRole('article', { name: /4 пара$/ });
    expect(within(ahead).getByRole('button', { name: 'Не можу' })).toBeInTheDocument();
  });

  it('без запитів блоку немає, а «Мої заміни» кажуть одним реченням, що буде далі', async () => {
    server.use(http.get(apiPath('/me/changes'), () => Response.json([])));
    renderWithProviders(
      <>
        <SubstitutionRequests teacherId={krentsin.id} />
        <MyUpcomingChanges />
      </>,
    );
    expect(await screen.findByText(/Нових запитів на заміну немає/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /чекають вашої відповіді|чекає вашої відповіді/ })).not.toBeInTheDocument();
  });

  it('заголовок каже, скільки замін чекає, а рядок — строк відповіді реченням, без табличок', async () => {
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    expect(await screen.findByRole('heading', { name: /^3\sзаміни чекають вашої відповіді$/ })).toBeInTheDocument();
    const requests = screen.getAllByRole('article', { name: /^Запит на заміну/ });
    const request = requests[requests.length - 1] as HTMLElement;
    const state = within(request).getByText((_, element) => element?.tagName === 'P' && /^Просять замінити · відповісти до \d\d:\d\d/.test(element.textContent ?? ''));
    // строк «до 07:30, 16 листопада» не розривається посередині
    expect(within(state).getByText(/^до \d\d:\d\d/)).toHaveClass('whitespace-nowrap');
    // скорочення причини з розпорядження («Відп.») викладачу не показуємо
    expect(within(request).queryByText(/Відп\./)).not.toBeInTheDocument();
  });

  it('повідомлення без відповіді не дублюються в «Моїх замінах»', async () => {
    renderWithProviders(
      <>
        <SubstitutionRequests teacherId={krentsin.id} />
        <MyUpcomingChanges />
      </>,
    );
    await screen.findAllByRole('article', { name: /^Нова заміна/ });
    expect(screen.queryByRole('region', { name: 'Мої заміни' })).not.toBeInTheDocument();
  });
});

describe('побажання', () => {
  it('цикл станів і різниця з збереженим', () => {
    expect([nextKind(null), nextKind('UNWANTED'), nextKind('UNAVAILABLE')]).toEqual(['UNWANTED', 'UNAVAILABLE', null]);
    const saved = toMap([{ weekday: 1, pair: 1, kind: 'UNWANTED', note: 'рано' }]);
    const draft = new Map(saved).set(cellKey(5, 6), 'UNAVAILABLE');
    draft.delete(cellKey(1, 1));
    expect(changedCells(saved, draft)).toBe(2);
    expect(toList(new Map<CellKey, PreferenceKind>([[cellKey(1, 1), 'UNAVAILABLE']]), [{ weekday: 1, pair: 1, kind: 'UNWANTED', note: 'рано' }])).toEqual([
      { weekday: 1, pair: 1, kind: 'UNAVAILABLE', note: 'рано' },
    ]);
  });

  it('мої пари — з базового розкладу семестру: половинка з позначкою тижня, обидві половинки й щотижнева — без', () => {
    const group = { id: 'g1', code: '4КІ1', course: 4 };
    const discipline = { id: 'd1', name: 'Сучасні технології проєктування', shortName: 'СТП', color: null };
    const cells = templateCells([
      { weekday: 2, pair: 3, parity: 'BOTH', group, subgroup: 0, discipline },
      { weekday: 3, pair: 1, parity: 'NUMERATOR', group, subgroup: 0, discipline },
      { weekday: 4, pair: 2, parity: 'NUMERATOR', group, subgroup: 2, discipline },
      { weekday: 4, pair: 2, parity: 'DENOMINATOR', group, subgroup: 2, discipline },
    ]);
    expect(cells.get(cellKey(2, 3))).toEqual(['4КІ1 · СТП']);
    expect(cells.get(cellKey(3, 1))).toEqual(['4КІ1 · СТП (чисельник)']);
    expect(cells.get(cellKey(4, 2))).toEqual(['4КІ1/2 · СТП']);
  });

  function Harness() {
    const [value, setValue] = useState<Map<CellKey, PreferenceKind>>(new Map());
    const slots = new Map<Weekday, BellSlotDto[]>(
      ([1, 2, 3, 4, 5] as const).map((d) => [d, Array.from({ length: d === 5 ? 6 : 8 }, (_, i) => ({ pair: i + 1, startsAt: '08:30', endsAt: '09:30' }))]),
    );
    return (
      <PreferenceGrid
        value={value}
        slotsByWeekday={slots}
        onCellChange={(key, kind) =>
          setValue((current) => {
            const next = new Map(current);
            if (kind) next.set(key, kind);
            else next.delete(key);
            return next;
          })
        }
      />
    );
  }

  it('сітка: клік, клавіші 1/2/0, стрілки; у пʼятницю немає 7–8 пар', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const monday1 = screen.getByRole('button', { name: /^Понеділок, 1 пара/ });
    await user.click(monday1);
    expect(monday1).toHaveAccessibleName(/небажано/);
    await user.keyboard('2');
    expect(monday1).toHaveAccessibleName(/неможливо/);
    await user.keyboard('0');
    expect(monday1).toHaveAccessibleName(/без побажань/);
    await user.keyboard('{ArrowRight}{ArrowDown}');
    expect(screen.getByRole('button', { name: /^Вівторок, 2 пара/ })).toHaveFocus();
    await act(async () => user.keyboard('1'));
    expect(screen.getByRole('button', { name: /^Вівторок, 2 пара/ })).toHaveAccessibleName(/небажано/);
    expect(screen.queryByRole('button', { name: /^Пʼятниця, 7 пара/ })).toBeNull();
    // зайві клітинки заштриховані без тексту на екрані, а пояснення — одним реченням під сіткою
    expect(screen.getAllByText('пари немає').every((element) => element.classList.contains('sr-only'))).toBe(true);
    expect(screen.getAllByText('пари немає')).toHaveLength(2);
    expect(screen.getByText(/^У пʼятницю лише 6\sпар\.$/)).toBeInTheDocument();
  });
});

describe('ключ і примітка до сітки побажань', () => {
  const slots = (counts: Record<number, number>) =>
    new Map<Weekday, BellSlotDto[]>(
      Object.entries(counts).map(([day, count]) => [Number(day) as Weekday, Array.from({ length: count }, (_, i) => ({ pair: i + 1, startsAt: '08:30', endsAt: '09:30' }))]),
    );

  it('ключ: значок і слово для кожного стану', () => {
    renderWithProviders(<PreferenceLegend />);
    expect(screen.getByText('небажано')).toBeInTheDocument();
    expect(screen.getByText('неможливо')).toBeInTheDocument();
  });

  it('коротші дні — одним реченням; однакові дні — без примітки', () => {
    expect(shorterDaysNote(slots({ 1: 8, 2: 8, 3: 8, 4: 6, 5: 6 }), 8)).toBe('У четвер і пʼятницю лише 6 пар.');
    expect(shorterDaysNote(slots({ 1: 8, 2: 8, 3: 7, 4: 8, 5: 6 }), 8)).toBe('У середу лише 7 пар. У пʼятницю лише 6 пар.');
    expect(shorterDaysNote(slots({ 1: 6, 2: 6, 3: 6, 4: 6, 5: 6 }), 6)).toBeNull();
  });
});

describe('схема відсутності', () => {
  const schema = absenceSchema('2026-11-09');

  it('минула відсутність, кінець раніше початку, порожні пари — помилки; та, що вже триває, — можна', () => {
    const result = schema.safeParse({ dateFrom: '2026-11-06', dateTo: '2026-11-05', wholeDay: false, pairs: [], reasonCategory: 'OTHER', comment: '' });
    expect(result.success).toBe(false);
    const paths = result.error?.issues.map((issue) => issue.path.join('.')).sort();
    // як core (ABSENCE_IN_PAST): минулою є відсутність, що вже закінчилась
    expect(paths).toEqual(['dateTo', 'dateTo', 'pairs']);
    const ongoing = schema.safeParse({ dateFrom: '2026-11-06', dateTo: '2026-11-10', wholeDay: true, pairs: [], reasonCategory: 'SICK_LEAVE', comment: '' });
    expect(ongoing.success).toBe(true);
    expect(schema.safeParse({ dateFrom: '2026-11-09', dateTo: '2026-11-09', wholeDay: true, pairs: [], comment: '' }).success).toBe(false);
  });

  it('у тіло запиту: увесь день — pairs null, коментар без пробілів', () => {
    const values = schema.parse({ dateFrom: '2026-11-10', dateTo: '2026-11-11', wholeDay: false, pairs: [4, 2], reasonCategory: 'SICK_LEAVE', comment: '  ' });
    expect(toAbsenceRequest(values)).toEqual({ dateFrom: '2026-11-10', dateTo: '2026-11-11', pairs: [2, 4], reasonCategory: 'SICK_LEAVE', comment: null });
    expect(toAbsenceRequest({ ...values, wholeDay: true }).pairs).toBeNull();
  });
});

describe('AbsenceForm: дні', () => {
  it('без причини: помилка стає на місце підказки, описує групу, а фокус іде на першу причину', async () => {
    window.localStorage.removeItem('rozklad.absenceReason');
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={teacherNamed('Непийвода').id} today="2026-11-09" />);
    expect(screen.getByText('Причину й коментар бачить лише диспетчер.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Повідомити диспетчера' }));

    const error = await screen.findByText('Оберіть причину');
    expect(screen.queryByText('Причину й коментар бачить лише диспетчер.')).not.toBeInTheDocument();
    const group = screen.getByRole('group', { name: 'Причина' });
    expect(group).toHaveAttribute('aria-describedby', error.id);
    expect(within(group).getAllByRole('radio')[0]).toHaveFocus();
  });

  it('«Сьогодні» в календарі — день застосунку (стенду), а не справжня дата браузера', () => {
    // 09.11.2026 не збігається зі справжньою датою запуску тестів
    renderWithProviders(<AbsenceForm teacherId={teacherNamed('Непийвода').id} today="2026-11-09" />);
    const todays = screen.getAllByRole('button', { name: /^Сьогодні, / });
    expect(todays).toHaveLength(1);
    expect(todays[0]).toHaveAccessibleName(/понеділок, 9-е листопада 2026/);
  });

  it('два кліки в календарі обирають кілька днів, третій починає новий вибір', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={teacherNamed('Непийвода').id} today="2026-11-09" />);
    const day = (name: RegExp) => screen.getByRole('button', { name });
    const summary = () => screen.getAllByRole('status').at(-1)?.textContent ?? '';

    await user.click(day(/четвер, 19/));
    expect(summary()).toMatch(/^Четвер, 19\sлистопада$/);
    await user.click(day(/пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/));
    expect(summary()).toMatch(/19–20\sлистопада, 2\sнавчальні дні/);

    await user.click(day(/вівторок, 24/));
    expect(summary()).toMatch(/^Вівторок, 24\sлистопада$/);
  });
});

describe('AbsenceForm: які пари зачепить', () => {
  const nepyivoda = teacherNamed('Непийвода');
  const day = (date: string, lessons: LessonView[]): DayDto => ({
    date,
    weekday: date === '2026-11-19' ? 4 : 5,
    weekdayLabel: date === '2026-11-19' ? 'ЧЕТВЕР' : 'ПʼЯТНИЦЯ',
    parity: 'NUMERATOR',
    studyDay: true,
    note: null,
    lessons,
  });
  const pair3: LessonView = {
    id: '00000000-0000-4000-8000-0000000000a3',
    pair: 3,
    startsAt: '11:20',
    endsAt: '12:20',
    discipline: { id: '00000000-0000-4000-8000-0000000000d1', shortName: 'НДЕКС', name: 'НДЕКС', color: null },
    teacher: { id: nepyivoda.id, shortName: 'Непийвода М.В.', displayName: 'Непийвода М.В.' },
    room: null,
    group: { id: '00000000-0000-4000-8000-0000000000b1', code: '4КІ3', course: 4 },
    subgroup: 0,
    kind: 'REGULAR',
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
  };
  let releaseFriday: () => void = () => {};

  beforeEach(() => {
    const fridayGate = new Promise<void>((resolve) => {
      releaseFriday = resolve;
    });
    server.use(
      http.get(apiPath('/me/schedule'), async ({ request }) => {
        const url = new URL(request.url);
        const from = url.searchParams.get('from') ?? '';
        const to = url.searchParams.get('to') ?? '';
        const days = [day('2026-11-19', [pair3]), day('2026-11-20', [])].filter((d) => d.date >= from && d.date <= to);
        if (from === '2026-11-20') await fridayGate;
        return Response.json({ owner: { type: 'TEACHER', id: nepyivoda.id, label: 'Непийвода М.В.' }, from, to, days });
      }),
    );
  });

  const preview = () => screen.getByText(/Зачепить|пар немає|Оберіть пари|не зачеплять|Перевіряємо/).closest('div')?.textContent ?? '';

  it('окремі пари — список справжніх пар у ці дні; номер поза розкладом — лише через «Інша пара…»', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.post(apiPath('/me/absences'), async ({ request }) => {
        bodies.push(await request.json());
        return problem({ status: 422, detail: 'Тест' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    await waitFor(() => expect(preview()).toMatch(/Зачепить 1\sпару:.*Чт, 19\.11 · 3 пара — 4КІ3, НДЕКС/));

    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    const own = await screen.findByRole('checkbox', { name: /^3 пара · 11:20\s*·\s*4КІ3, НДЕКС$/ });
    // номерів 1–8 одразу немає: лише «Інша пара…» для рідкісного випадку
    expect(screen.queryByRole('button', { name: '1 пара' })).not.toBeInTheDocument();
    await user.click(own);
    expect(own).toBeChecked();

    await user.click(screen.getByRole('button', { name: 'Інша пара…' }));
    expect(screen.queryByRole('button', { name: '3 пара' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '1 пара' }));

    await user.click(screen.getByRole('radio', { name: 'Лікарняний' }));
    await user.click(screen.getByRole('button', { name: 'Повідомити диспетчера' }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ dateFrom: '2026-11-19', dateTo: '2026-11-19', pairs: [1, 3], reasonCategory: 'SICK_LEAVE' });
  });

  it('окремі пари в день без моїх пар: одразу номери пар, бо обирати більше нічого', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    await user.click(screen.getByRole('button', { name: /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/ }));
    releaseFriday();
    expect(await screen.findByText('У ці дні за розкладом у вас пар немає.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^\d пара$/ })).toHaveLength(8);
  });

  function trackAbsences() {
    const bodies: unknown[] = [];
    server.use(
      http.post(apiPath('/me/absences'), async ({ request }) => {
        bodies.push(await request.json());
        return problem({ status: 422, detail: 'Тест' });
      }),
    );
    return bodies;
  }

  it('розклад не відповів — окремі пари все одно можна обрати за номером і надіслати', async () => {
    server.use(http.get(apiPath('/me/schedule'), () => problem({ status: 503, detail: 'Сервіс недоступний' })));
    const bodies = trackAbsences();
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    expect(await screen.findByText('Не вдалося перевірити ваш розклад — оберіть номери пар нижче.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^\d пара$/ })).toHaveLength(8);

    await user.click(screen.getByRole('button', { name: '2 пара' }));
    await user.click(screen.getByRole('radio', { name: 'Лікарняний' }));
    await user.click(screen.getByRole('button', { name: 'Повідомити диспетчера' }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ dateFrom: '2026-11-19', dateTo: '2026-11-19', pairs: [2] });
  });

  it('понад 31 день — розклад не перевіряємо й кажемо про це, а не «Перевіряємо…» без кінця', async () => {
    const requested: string[] = [];
    // лише записує запит; відповідає обробник describe (без return MSW іде до наступного)
    server.use(
      http.get(apiPath('/me/schedule'), ({ request }) => {
        requested.push(request.url);
      }),
    );
    const bodies = trackAbsences();
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('button', { name: /вівторок, 10/ }));
    await user.click(screen.getByRole('button', { name: /наступн/i }));
    await user.click(screen.getByRole('button', { name: /вівторок, 15/ }));
    // підсумок під календарем — обраний діапазон, а не «Оберіть день»
    expect(screen.getAllByRole('status').at(-1)?.textContent).toMatch(/^10.+15\sгрудня/);
    expect(screen.getByText(/Розклад перевіряємо лише на 31 день/)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    expect(screen.queryByText(/Перевіряємо/)).not.toBeInTheDocument();
    expect(screen.getByText('Розклад перевіряємо лише на 31 день — оберіть номери пар нижче.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^\d пара$/ })).toHaveLength(8);
    await user.click(screen.getByRole('button', { name: '1 пара' }));
    await user.click(screen.getByRole('button', { name: '5 пара' }));
    await user.click(screen.getByRole('radio', { name: 'Відрядження' }));
    await user.click(screen.getByRole('button', { name: 'Повідомити диспетчера' }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ dateFrom: '2026-11-10', dateTo: '2026-12-15', pairs: [1, 5], reasonCategory: 'BUSINESS_TRIP' });
    expect(requested.filter((url) => url.includes('to=2026-12-15'))).toEqual([]);
  });

  it('поки вантажиться новий діапазон, не показує пари попереднього', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    await waitFor(() => expect(preview()).toContain('Чт, 19.11 · 3 пара'));
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    await user.click(screen.getByRole('button', { name: /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/ }));
    await user.click(screen.getByRole('button', { name: /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/ }));

    expect(screen.getAllByRole('status').at(-1)?.textContent).toMatch(/^П.ятниця, 20\sлистопада$/);
    expect(preview()).toBe('Перевіряємо ваші пари на ці дні…');
    expect(preview()).not.toContain('19.11');

    releaseFriday();
    await waitFor(() => expect(preview()).toBe('У ці дні за розкладом у вас пар немає.'));
  });
});

describe('AbsenceList: видалення зі «Скасувати»', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function trackDeletes() {
    const deleted: string[] = [];
    server.use(
      http.delete(apiPath('/me/absences/:id'), ({ params }) => {
        deleted.push(String(params.id));
        return new Response(null, { status: 204 });
      }),
    );
    return deleted;
  }

  it('рядок зникає одразу без «Ви впевнені?», а «Скасувати» повертає його й нічого не видаляє', async () => {
    const deleted = trackDeletes();
    const undo = vi.spyOn(notify, 'undo');
    const user = userEvent.setup();
    renderWithProviders(<AbsenceList today="2026-11-09" />);
    const button = await screen.findByRole('button', { name: /^Видалити відсутність / });
    const range = (button.getAttribute('aria-label') ?? '').replace('Видалити відсутність ', '');
    await user.click(button);

    expect(screen.queryByRole('button', { name: /^Видалити відсутність / })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(undo).toHaveBeenCalledWith(`Відсутність ${range} видалено`, expect.objectContaining({ duration: 8000 }));

    act(() => undo.mock.calls[0]?.[1].onUndo());
    expect(await screen.findByRole('button', { name: `Видалити відсутність ${range}` })).toBeInTheDocument();
    expect(deleted).toEqual([]);
  });

  it('видалення пішло за таймером — «Скасувати» зникає, а пізній клік каже, що вже видалено', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
    const deleted = trackDeletes();
    const undo = vi.spyOn(notify, 'undo');
    const dismiss = vi.spyOn(notify, 'dismiss');
    const info = vi.spyOn(notify, 'info');
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<AbsenceList today="2026-11-09" />);
    await user.click(await screen.findByRole('button', { name: /^Видалити відсутність / }));
    expect(dismiss).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(8000);
    });
    await waitFor(() => expect(deleted).toHaveLength(1));
    expect(dismiss).toHaveBeenCalledWith(undo.mock.results[0]?.value);

    act(() => undo.mock.calls[0]?.[1].onUndo());
    expect(info).toHaveBeenCalledWith('Відсутність уже видалено, скасувати не вийде');
    expect(deleted).toHaveLength(1);
  });

  it('піти зі сторінки, не скасувавши, — видалення йде одразу', async () => {
    const deleted = trackDeletes();
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<AbsenceList today="2026-11-09" />);
    await user.click(await screen.findByRole('button', { name: /^Видалити відсутність / }));
    expect(deleted).toEqual([]);
    unmount();
    await waitFor(() => expect(deleted).toHaveLength(1));
  });
});

describe('мій день реченням', () => {
  const lesson = (pair: number, startsAt: string, teacherId: string, kind: LessonView['kind'] = 'REGULAR'): LessonView => ({
    id: `l-${pair}-${teacherId}`,
    pair,
    startsAt,
    endsAt: '00:00',
    discipline: { id: 'd1', shortName: 'СТП', name: 'Сучасні технології проєктування', color: null },
    teacher: { id: teacherId, shortName: 'Т', displayName: 'Т' },
    room: { id: 'r1', name: '14', type: 'CLASSROOM' },
    group: { id: 'g1', code: '4КІ2', course: 4 },
    subgroup: 0,
    kind,
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
  });
  const day = (date: string, lessons: LessonView[]): DayDto => ({ date, weekday: 1, weekdayLabel: '', parity: 'NUMERATOR', studyDay: true, note: null, lessons });

  it('що веду: дисципліна, група, аудиторія', () => {
    expect(lessonPhrase(lesson(1, '08:30', 'me'))).toBe('СТП, 4КІ2, ауд. 14');
  });

  it('наступний день з моїми парами: чужі й скасовані пари не рахуються', () => {
    const days = [
      day('2026-11-09', [lesson(1, '08:30', 'me')]),
      day('2026-11-10', [lesson(1, '08:30', 'other'), lesson(2, '09:40', 'me', 'CANCELLED')]),
      day('2026-11-11', [lesson(3, '11:00', 'me'), lesson(2, '09:40', 'me')]),
    ];
    expect(nextDaySentence(days, '2026-11-09', 'me')).toMatch(/^Завтра пар немає\. У середу, 11\sлистопада, перша о 09:40 — СТП, 4КІ2, ауд\.\s14\.$/);
    expect(nextDaySentence(days, '2026-11-10', 'me')).toBe('Завтра перша о 09:40 — СТП, 4КІ2, ауд. 14.');
    expect(nextDaySentence(days, '2026-11-11', 'me')).toBeNull();
  });

  it('після пʼятниці — без «завтра пар немає»; скорочення в кінці не дає двох крапок', () => {
    const gym: LessonView = { ...lesson(1, '08:30', 'me'), discipline: { id: 'd2', shortName: 'ФВ', name: 'Фізичне виховання', color: null }, room: { id: 'r2', name: 'с.з.', type: 'GYM' } };
    expect(nextDaySentence([day('2026-11-16', [gym])], '2026-11-13', 'me')).toMatch(/^У понеділок, 16\sлистопада, перша о 08:30 — ФВ, 4КІ2, с\.з\.$/);
    expect(nextDaySentence([day('2026-11-12', [gym])], '2026-11-11', 'me')).toBe('Завтра перша о 08:30 — ФВ, 4КІ2, с.з.');
    expect(endSentence('ауд. 14')).toBe('ауд. 14.');
    // пара без дзвінків (заміна, дзвінки ще не прийшли) — номером, а не «о null»
    const untimed = { ...lesson(2, '08:30', 'me'), startsAt: null };
    expect(nextDaySentence([day('2026-11-12', [untimed])], '2026-11-11', 'me')).toMatch(/^Завтра перша — 2\u00a0пара, СТП, 4КІ2, ауд\.\s14\.$/);
  });

  it('запит на заміну, що чекає відповіді, — у реченні «що далі», а не поруч із «завтра пар немає»', () => {
    const request = { id: 'r', status: 'PENDING_CONFIRMATION', supersededBy: null, date: '2026-11-13', pairFrom: 1 } as unknown as ChangeDto;
    const monday = 'Завтра пар немає. У понеділок, 16\u00a0листопада, перша о 09:40 — СТП, 4КІ1, ауд. 301.';
    expect(withRequest(monday, request, '2026-11-12', '2026-11-16', '08:30')).toBe(
      'Завтра пар немає, але є запит на заміну\u00a0— 1\u00a0пара о\u00a008:30. У понеділок, 16\u00a0листопада, перша о 09:40 — СТП, 4КІ1, ауд. 301.',
    );
    // завтра є й мої пари: запит — окремим реченням після них
    expect(withRequest('Завтра перша о 09:40 — СТП, 4КІ1, ауд. 301.', request, '2026-11-12', '2026-11-13', '08:30')).toBe(
      'Завтра перша о 09:40 — СТП, 4КІ1, ауд. 301. Ще є запит на заміну\u00a0— 1\u00a0пара о\u00a008:30.',
    );
    // дзвінки ще не прийшли — без часу; попереду пар немає — лише запит
    expect(withRequest(null, request, '2026-11-12', null)).toBe('Завтра є запит на заміну\u00a0— 1\u00a0пара.');
    // запит пізніше за мою наступну пару не згадуємо, раніше — так
    const later = { ...request, date: '2026-11-17' };
    expect(withRequest(monday, later, '2026-11-12', '2026-11-16', '08:30')).toBe(monday);
    expect(withRequest('Наступного тижня пар у вас немає.', later, '2026-11-12', null, '08:30')).toBe(
      'Наступного тижня пар у вас немає. Є запит на заміну на вівторок, 17\u00a0листопада\u00a0— 1\u00a0пара о\u00a008:30.',
    );
    expect(withRequest(monday, null, '2026-11-12', '2026-11-16')).toBe(monday);
  });

  it('найближчий запит: лише прохання без відповіді, не з минулих днів і не передане доповненням', () => {
    const base = { status: 'PENDING_CONFIRMATION', supersededBy: null } as unknown as ChangeDto;
    const list = [
      { ...base, id: 'past', date: '2026-11-11', pairFrom: 1 },
      { ...base, id: 'notice', date: '2026-11-12', pairFrom: 1, status: 'CONFIRMED' },
      { ...base, id: 'gone', date: '2026-11-12', pairFrom: 2, supersededBy: { changeId: 'c2', kind: 'CANCEL', orderId: null } },
      { ...base, id: 'late', date: '2026-11-13', pairFrom: 3 },
      { ...base, id: 'first', date: '2026-11-13', pairFrom: 1 },
    ] as ChangeDto[];
    expect(nextRequest(list, '2026-11-12')?.id).toBe('first');
    expect(nextRequest(list.slice(0, 3), '2026-11-12')).toBeNull();
  });

  it('сьогоднішній запит на пару, що вже скінчилася, не «найближчий»: є пізніший — він, немає — нічого', () => {
    const base = { status: 'PENDING_CONFIRMATION', supersededBy: null } as unknown as ChangeDto;
    const bells: BellSlotDto[] = [
      { pair: 1, startsAt: '08:30', endsAt: '09:30' },
      { pair: 2, startsAt: '09:40', endsAt: '10:40' },
      { pair: 3, startsAt: '11:00', endsAt: '12:00' },
    ];
    const list = [
      { ...base, id: 'over', date: '2026-11-20', pairFrom: 1, pairTo: 1 },
      { ...base, id: 'later', date: '2026-11-20', pairFrom: 3, pairTo: 3 },
      { ...base, id: 'monday', date: '2026-11-23', pairFrom: 1, pairTo: 1 },
    ] as ChangeDto[];
    const at = (time: string) => ({ bells, minutes: Number(time.slice(0, 2)) * 60 + Number(time.slice(3)) });
    expect(nextRequest(list, '2026-11-20', at('08:00'))?.id).toBe('over');
    expect(nextRequest(list, '2026-11-20', at('10:10'))?.id).toBe('later');
    expect(nextRequest(list, '2026-11-20', at('12:00'))?.id).toBe('monday');
    // пара сьогодні ще йде: запит лишається; без дзвінків не знаємо, що пара минула
    expect(nextRequest([list[1] as ChangeDto], '2026-11-20', at('11:30'))?.id).toBe('later');
    expect(nextRequest([list[0] as ChangeDto], '2026-11-20', { bells: [], minutes: 900 })?.id).toBe('over');
  });

  it('підтверджені заміни дня — мої пари, доки розпорядження не опубліковане', () => {
    const base = {
      date: '2026-11-20',
      status: 'CONFIRMED',
      supersededBy: null,
      teacherAnswer: null,
      group: { code: '4КІ1' },
      original: { discipline: { shortName: 'КБ' }, teacher: null, room: { name: '14' } },
      new: { discipline: { shortName: 'СТП' }, teacher: null, room: null },
    } as unknown as ChangeDto;
    const list = [
      { ...base, id: 'block', pairFrom: 4, pairTo: 5 },
      { ...base, id: 'notice', pairFrom: 2, pairTo: 2, approvalMode: 'NOTIFY' },
      { ...base, id: 'asked', pairFrom: 1, pairTo: 1, status: 'PENDING_CONFIRMATION' },
      { ...base, id: 'declined', pairFrom: 3, pairTo: 3, teacherAnswer: 'DECLINE' },
      { ...base, id: 'published', pairFrom: 3, pairTo: 3, status: 'APPLIED' },
      { ...base, id: 'gone', pairFrom: 3, pairTo: 3, supersededBy: { changeId: 'x', kind: 'CANCEL', orderId: 'o' } },
      { ...base, id: 'tomorrow', date: '2026-11-21', pairFrom: 1, pairTo: 1 },
    ] as ChangeDto[];
    const today = confirmedAssignments(list, '2026-11-20');
    expect(today.map((c) => c.id)).toEqual(['notice', 'block']);
    expect(assignmentPairs(today)).toEqual([2, 4, 5]);
    // аудиторія лишається тією самою, якщо рішення її не міняло
    expect(assignmentPhrase(today[0] as ChangeDto)).toBe('заміна: СТП, 4КІ1, ауд. 14');
  });
});

describe('MyNow: підтверджена заміна сьогодні', () => {
  const today = '2026-11-20';
  const bells: BellSlotDto[] = [
    { pair: 1, startsAt: '08:30', endsAt: '09:30' },
    { pair: 2, startsAt: '09:40', endsAt: '10:40' },
    { pair: 3, startsAt: '11:00', endsAt: '12:00' },
  ];
  const friday = { date: today, weekday: 5, weekdayLabel: 'ПЯТНИЦЯ', parity: 'DENOMINATOR', studyDay: true, note: null, lessons: [] } as unknown as DayDto;
  const change = (patch: Partial<ChangeDto>): ChangeDto =>
    ({
      id: 'c',
      kind: 'REPLACE_DISCIPLINE',
      date: today,
      pairFrom: 2,
      pairTo: 2,
      subgroup: 0,
      status: 'CONFIRMED',
      approvalMode: 'NOTIFY',
      teacherAnswer: null,
      supersededBy: null,
      group: { id: 'g', code: '4КІ1' },
      original: { discipline: { id: 'd1', shortName: 'КБ', name: 'Кібербезпека' }, teacher: null, room: { id: 'r', name: '14' } },
      new: { discipline: { id: 'd2', shortName: 'СТП', name: 'СТП' }, teacher: { id: krentsin.id }, room: null },
      ...patch,
    }) as unknown as ChangeDto;
  const pastRequest = () => change({ id: 'old', status: 'PENDING_CONFIRMATION', approvalMode: 'CONFIRM', pairFrom: 1, pairTo: 1 });

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // пʼятниця, 10:10 за Києвом — посеред 2 пари
    vi.setSystemTime(new Date('2026-11-20T08:10:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('у розкладі пар немає, але є підтверджена заміна — «зараз» про неї, а не «Сьогодні пар немає»', async () => {
    server.use(http.get(apiPath('/me/changes'), () => Response.json([change({}), pastRequest()])));
    renderWithProviders(<MyNow teacherId={krentsin.id} today={today} days={[friday]} bells={bells} />);
    expect(await screen.findByText('заміна: СТП, 4КІ1, ауд. 14')).toBeInTheDocument();
    expect(screen.getByText(/Зараз у вас/)).toBeInTheDocument();
    expect(screen.queryByText('Сьогодні пар немає')).not.toBeInTheDocument();
    expect(screen.queryByText(/запит на заміну/)).not.toBeInTheDocument();
  });

  it('без замін на сьогодні — «Сьогодні пар немає» і без запиту на пару, що вже минула', async () => {
    server.use(http.get(apiPath('/me/changes'), () => Response.json([pastRequest()])));
    renderWithProviders(<MyNow teacherId={krentsin.id} today={today} days={[friday]} bells={bells} />);
    expect(await screen.findByText('Сьогодні пар немає')).toBeInTheDocument();
    expect(screen.queryByText(/Сьогодні ще є запит/)).not.toBeInTheDocument();
  });
});

describe('тексти заміни', () => {
  const change = (patch: Partial<ChangeDto>): ChangeDto =>
    ({
      date: '2026-11-13',
      kind: 'REPLACE_TEACHER',
      original: { discipline: { shortName: 'КБ' }, teacher: { displayName: 'Непийвода' } },
      ...patch,
    }) as unknown as ChangeDto;

  it('коли: сьогодні, завтра або короткий день з датою', () => {
    expect(changeWhen(change({ date: '2026-11-09' }), '2026-11-09')).toBe('Сьогодні');
    expect(changeWhen(change({ date: '2026-11-10' }), '2026-11-09')).toBe('Завтра');
    expect(changeWhen(change({}), '2026-11-09')).toBe('Пт, 13 листопада');
  });

  it('кого заміняю: дисципліну згадуємо, лише коли вона теж інша', () => {
    expect(changeReplaced(change({}))).toBe('Непийвода');
    expect(changeReplaced(change({ kind: 'REPLACE_DISCIPLINE' }))).toBe('КБ, Непийвода');
  });

  it('строк відповіді: сьогодні — лише час, завтра — словом, далі — датою', () => {
    expect(deadlineDay(new Date('2026-11-09T15:00:00Z'), '2026-11-09')).toBe('');
    expect(deadlineDay(new Date('2026-11-10T05:30:00Z'), '2026-11-09')).toBe(' завтра');
    expect(deadlineDay(new Date('2026-11-12T05:30:00Z'), '2026-11-09')).toBe(', 12 листопада');
  });
});

describe('changeStatusBadge', () => {
  const applied = { status: 'APPLIED', supersededBy: null } as unknown as ChangeDto;

  it('застосована заміна — «У розпорядженні», доки її не змінило доповнення', () => {
    expect(changeStatusBadge(applied)?.label).toBe('У розпорядженні');
    expect(changeStatusBadge({ ...applied, status: 'CONFIRMED' })?.label).toBe('Підтверджено');
  });

  it('заміну, яку скасувало чи передало іншому доповнення, видно як скасовану, а не «У розпорядженні»', () => {
    const later = (kind: ChangeDto['kind']): ChangeDto => ({ ...applied, supersededBy: { changeId: 'c2', kind, orderId: 'o49' } });
    expect(changeStatusBadge(later('CANCEL'))).toEqual({ label: 'Скасовано доповненням', tone: 'cancel' });
    expect(changeStatusBadge(later('REPLACE_TEACHER'))?.label).toBe('Передано іншому доповненням');
    expect(changeStatusBadge(later('SWAP'))?.label).toBe('Змінено доповненням');
  });

  it('NOTIFY: «Не можу» після розпорядження видно як заперечення', () => {
    expect(changeStatusBadge({ ...applied, approvalMode: 'NOTIFY', teacherAnswer: 'DECLINE' })).toEqual({
      label: 'Ви повідомили, що не можете',
      tone: 'cancel',
    });
    expect(changeStatusBadge({ ...applied, approvalMode: 'NOTIFY', teacherAnswer: 'CONFIRM' })?.label).toBe('У розпорядженні');
  });
});

describe('відповідь на заміну (як у core)', () => {
  const base = { status: 'CONFIRMED', approvalMode: 'NOTIFY', teacherAnswer: null } as unknown as ChangeDto;
  const at = '2026-11-09T08:00:00Z';

  it('повідомлення: «Підтверджую» лишає статус, «Не можу» відхиляє неопубліковану й лишає опубліковану', () => {
    expect(answered(base, 'CONFIRM', at)).toMatchObject({ status: 'CONFIRMED', teacherAnswer: 'CONFIRM', respondedAt: at });
    expect(answered(base, 'DECLINE', at)).toMatchObject({ status: 'DECLINED', teacherAnswer: 'DECLINE' });
    expect(answered({ ...base, status: 'APPLIED' }, 'DECLINE', at)).toMatchObject({ status: 'APPLIED', teacherAnswer: 'DECLINE' });
  });

  it('прохання: підтвердження чи відмова змінюють статус', () => {
    const request = { ...base, status: 'PENDING_CONFIRMATION', approvalMode: 'CONFIRM' } as ChangeDto;
    expect(answered(request, 'CONFIRM', at).status).toBe('CONFIRMED');
    expect(answered(request, 'DECLINE', at).status).toBe('DECLINED');
  });

  it('заміна, яку доповнення скасувало чи передало іншому, відповіді не чекає', () => {
    const notice = { ...base, id: 'gone', status: 'APPLIED', date: '2026-11-13', pairFrom: 2 } as ChangeDto;
    expect(awaitingAnswer([notice], '2026-11-12')).toHaveLength(1);
    for (const kind of ['CANCEL', 'REPLACE_TEACHER', 'REPLACE_DISCIPLINE', 'MERGE_SUBGROUPS', 'SWAP'] as const) {
      const superseded: ChangeDto = { ...notice, supersededBy: { changeId: 'c2', kind, orderId: 'o49' } };
      expect(isUnansweredNotice(superseded, '2026-11-12')).toBe(false);
      expect(awaitingAnswer([superseded], '2026-11-12')).toEqual([]);
    }
  });

  it('у списку «Нові заміни» — прохання й повідомлення без відповіді, не з минулих днів', () => {
    const list = [
      { ...base, id: 'past', date: '2026-11-06', pairFrom: 1 },
      { ...base, id: 'notice', date: '2026-11-10', pairFrom: 2 },
      { ...base, id: 'answered', date: '2026-11-10', pairFrom: 3, teacherAnswer: 'CONFIRM' },
      { ...base, id: 'request', date: '2026-11-10', pairFrom: 1, status: 'PENDING_CONFIRMATION', approvalMode: 'CONFIRM' },
      { ...base, id: 'auto', date: '2026-11-10', pairFrom: 4, approvalMode: 'AUTO' },
    ] as ChangeDto[];
    expect(awaitingAnswer(list, '2026-11-09').map((c) => c.id)).toEqual(['request', 'notice']);
  });
});

describe('одне правило в кабінеті: підтверджена, ще не опублікована заміна — моя пара всюди', () => {
  const today = '2026-11-20';
  const bells: BellSlotDto[] = [
    { pair: 1, startsAt: '08:30', endsAt: '09:30' },
    { pair: 2, startsAt: '09:40', endsAt: '10:40' },
  ];
  const friday = { date: today, weekday: 5, weekdayLabel: 'ПЯТНИЦЯ', parity: 'DENOMINATOR', studyDay: true, note: null, lessons: [] } as unknown as DayDto;
  const change = (patch: Partial<ChangeDto>): ChangeDto =>
    ({
      id: 'c',
      kind: 'REPLACE_DISCIPLINE',
      date: today,
      pairFrom: 2,
      pairTo: 2,
      subgroup: 0,
      status: 'CONFIRMED',
      approvalMode: 'NOTIFY',
      teacherAnswer: null,
      supersededBy: null,
      printedReason: 'Зв.зам.',
      group: { id: 'g', code: '4КІ1', course: 4 },
      original: {
        discipline: { id: 'd1', shortName: 'КБ', name: 'Кібербезпека' },
        teacher: { id: 't0', shortName: 'Непийвода', displayName: 'Непийвода' },
        room: { id: 'r', name: '14' },
      },
      new: {
        discipline: { id: 'd2', shortName: 'СТП', name: 'Сучасні технології проєктування' },
        teacher: { id: krentsin.id, shortName: 'Кренцін', displayName: 'Кренцін' },
        room: null,
      },
      ...patch,
    }) as unknown as ChangeDto;

  it('заняття із заміни стоїть у розкладі дня так, як його покаже розпорядження', () => {
    const merged = withAssignments({ days: [friday] }, [change({})], new Map([[today, bells]]));
    const lesson = merged.days[0]!.lessons[0]!;
    expect(lesson).toMatchObject({
      pair: 2,
      startsAt: '09:40',
      endsAt: '10:40',
      kind: 'SUBSTITUTED',
      changeId: 'c',
      reasonText: 'Зв.зам.',
      discipline: { shortName: 'СТП' },
      room: { name: '14' },
      group: { code: '4КІ1' },
    });
    expect(lesson.original).toMatchObject({ discipline: { shortName: 'КБ' }, teacher: { shortName: 'Непийвода' } });
  });

  it('не додає запит, що ще чекає відповіді, відхилену, передану доповненням чи вже опубліковану заміну', () => {
    const published = { ...friday, lessons: [{ id: 'l', pair: 2, changeId: 'c', subgroup: 0 } as LessonView] };
    const schedule = { days: [friday] };
    expect(withAssignments(schedule, [change({ status: 'PENDING_CONFIRMATION' })])).toBe(schedule);
    expect(withAssignments(schedule, [change({ teacherAnswer: 'DECLINE' })])).toBe(schedule);
    expect(withAssignments(schedule, [change({ supersededBy: { changeId: 'x', orderId: 'o', kind: 'CANCEL' } as unknown as ChangeDto['supersededBy'] })])).toBe(schedule);
    expect(withAssignments({ days: [published] }, [change({})]).days[0]!.lessons).toHaveLength(1);
    // без дзвінків — пара без часу, а не вигаданий час
    expect(withAssignments(schedule, [change({})]).days[0]!.lessons[0]).toMatchObject({ startsAt: null, endsAt: null });
  });

  it('блок — одне заняття на кілька пар, додаткова пара — «Додано» на кожну пару', () => {
    const [block] = assignmentLessons(change({ kind: 'BLOCK', pairFrom: 1, pairTo: 2 }), bells);
    expect(block).toMatchObject({ pair: 1, startsAt: '08:30', endsAt: '10:40', kind: 'BLOCK' });
    expect(assignmentLessons(change({ kind: 'ADDITIONAL', pairFrom: 1, pairTo: 2 }), bells).map((l) => [l.pair, l.kind])).toEqual([
      [1, 'ADDITIONAL'],
      [2, 'ADDITIONAL'],
    ]);
  });

  it('зміна моєї ж пари (аудиторія, дисципліна, скасування) — не друга клітинка «Заміна»', () => {
    const me = { id: krentsin.id, shortName: 'Кренцін', displayName: 'Кренцін' };
    const own = change({}).original;
    const mine = { ...own, teacher: me };
    const schedule = { days: [friday] };
    expect(withAssignments(schedule, [change({ kind: 'ROOM_CHANGE', original: mine, new: { discipline: null, teacher: me, room: { id: 'r2', name: '301' } } as ChangeDto['new'] })])).toBe(schedule);
    expect(withAssignments(schedule, [change({ original: mine, new: { ...change({}).new, teacher: null } })])).toBe(schedule);
    expect(withAssignments(schedule, [change({ kind: 'CANCEL', original: mine })])).toBe(schedule);
    // а пара іншого викладача, яку веду я, — так
    expect(withAssignments(schedule, [change({})]).days[0]!.lessons).toHaveLength(1);
  });

  it('після останньої пари «що далі» бачить завтрашню підтверджену заміну, як і сітка', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // четвер, 19 листопада, 16:00 за Києвом: сьогодні пар немає, завтра — лише заміна
    vi.setSystemTime(new Date('2026-11-19T14:00:00Z'));
    try {
      const thursday = { ...friday, date: '2026-11-19', weekday: 4, weekdayLabel: 'ЧЕТВЕР' } as DayDto;
      server.use(
        http.get(apiPath('/me/changes'), () => Response.json([change({})])),
        http.get(apiPath('/public/bells'), () => Response.json({ profile: { id: 'p', name: 'Основний' }, date: today, weekday: 5, studyDay: true, slots: bells })),
      );
      renderWithProviders(<MyNow teacherId={krentsin.id} today="2026-11-19" days={[thursday, friday]} bells={bells} />);
      expect(await screen.findByText('Завтра перша о 09:40 — СТП, 4КІ1, ауд. 14.')).toBeInTheDocument();
      expect(screen.queryByText(/Завтра пар немає/)).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('«Мої пари» показують ту саму заміну, яку «зараз» називає поточною парою (з часом із дзвінків)', async () => {
    server.use(
      http.get(apiPath('/me/changes'), () => Response.json([change({})])),
      http.get(apiPath('/public/bells'), () => Response.json({ profile: { id: 'p', name: 'Основний' }, date: today, weekday: 5, studyDay: true, slots: bells })),
    );
    const schedule = { days: [friday] };
    function Probe() {
      const merged = useAssignedSchedule(schedule);
      return <p>{merged?.days[0]?.lessons.map((lesson) => `${lesson.pair}:${lesson.discipline.shortName}:${lesson.startsAt ?? '-'}`).join(',')}</p>;
    }
    renderWithProviders(<Probe />);
    expect(await screen.findByText('2:СТП:09:40')).toBeInTheDocument();
  });
});

describe('рядки запитів про минулу пару', () => {
  it('пара минула за дзвінком — сьогодні після кінця останньої пари заміни або в минулий день', () => {
    const bells = [
      { pair: 1, endsAt: '09:30' },
      { pair: 2, endsAt: '10:40' },
    ];
    expect(changePassed({ date: '2026-11-20', pairTo: 1 }, '2026-11-20', { bells, minutes: 10 * 60 })).toBe(true);
    expect(changePassed({ date: '2026-11-20', pairTo: 2 }, '2026-11-20', { bells, minutes: 10 * 60 })).toBe(false);
    expect(changePassed({ date: '2026-11-19', pairTo: 2 }, '2026-11-20', null)).toBe(true);
    // без дзвінків не знаємо — тож ні
    expect(changePassed({ date: '2026-11-20', pairTo: 1 }, '2026-11-20', null)).toBe(false);
  });

  it('прохання про минулу пару не буває найтерміновішим: заповнена кнопка не стоїть у рядку без «Не можу»', () => {
    const now = new Date('2026-11-20T08:00:00Z');
    const list = [
      { id: 'past', status: 'PENDING_CONFIRMATION', confirmDeadline: '2026-11-20T09:00:00Z' },
      { id: 'later', status: 'PENDING_CONFIRMATION', confirmDeadline: '2026-11-21T09:00:00Z' },
    ] as ChangeDto[];
    expect(mostUrgent(list, now)).toBe('past');
    expect(mostUrgent(list, now, (change) => change.id === 'past')).toBe('later');
    expect(mostUrgent(list.slice(0, 1), now, () => true)).toBeNull();
  });

  describe('у списку', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      // 10:05 за Києвом — 1 пара 9 листопада вже скінчилася
      vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
    });
    afterEach(() => vi.useRealTimers());

    it('минула пара: звичайна «Підтверджую» і пояснення замість «Не можу»; заповнена — у живому рядку', async () => {
      const request = changesForTeacher(krentsin.id, new Date()).find((change) => change.status === 'PENDING_CONFIRMATION');
      if (!request) throw new Error('у фікстурах немає запиту CONFIRM');
      server.use(
        http.get(apiPath('/me/changes'), () =>
          Response.json([
            { ...request, id: 'over', date: '2026-11-09', pairFrom: 1, pairTo: 1, confirmDeadline: '2026-11-09T12:00:00Z', supersededBy: null },
            { ...request, id: 'ahead', date: '2026-11-10', pairFrom: 2, pairTo: 2, confirmDeadline: '2026-11-10T05:00:00Z', supersededBy: null },
          ]),
        ),
      );
      renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
      const over = await screen.findByRole('article', { name: /1 пара$/ });
      await waitFor(() => expect(within(over).getByText('Відмовитися вже не можна')).toBeInTheDocument());
      expect(within(over).queryByRole('button', { name: 'Не можу' })).not.toBeInTheDocument();
      await waitFor(() => expect(within(over).getByRole('button', { name: 'Підтверджую' }).className).not.toMatch(/bg-accent/));
      const ahead = screen.getByRole('article', { name: /2 пара$/ });
      await waitFor(() => expect(within(ahead).getByRole('button', { name: 'Підтверджую' }).className).toMatch(/bg-accent/));
      expect(within(ahead).getByRole('button', { name: 'Не можу' })).toBeInTheDocument();
    });
  });
});
