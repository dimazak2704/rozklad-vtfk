import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BellSlotDto, ChangeDto, DayDto, LessonView, PreferenceKind, Weekday } from '@/api/types';
import { notify } from '@/components/ui';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { absenceSchema, toAbsenceRequest } from './absence-schema';
import { AbsenceForm } from './AbsenceForm';
import { AbsenceList } from './AbsenceList';
import { awaitingAnswer, changeReplaced, changeStatusBadge, changeWhen, deadlineDay, mostUrgent, REPLY_UNDO_MS } from './change-text';
import { endSentence, lessonPhrase, nextDaySentence } from './my-day';
import { MyUpcomingChanges } from './MyUpcomingChanges';
import { PreferenceGrid } from './PreferenceGrid';
import { cellKey, changedCells, nextKind, toList, toMap, type CellKey } from './preferences';
import { SubstitutionRequests } from './SubstitutionRequests';
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
    expect(screen.getAllByText('пари немає')).toHaveLength(2);
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
