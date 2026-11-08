import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BellSlotDto, ChangeDto, DayDto, LessonView, PreferenceKind, Weekday } from '@/api/types';
import { ConfirmDialogHost, notify } from '@/components/ui';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { absenceSchema, toAbsenceRequest } from './absence-schema';
import { AbsenceForm } from './AbsenceForm';
import { AbsenceList } from './AbsenceList';
import { awaitingAnswer, changeStatusBadge } from './change-text';
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
  afterEach(() => vi.useRealTimers());

  it('підтвердження оптимістичне: картка зникає одразу', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const requests = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    expect(requests).toHaveLength(2);
    await user.click(within(requests[0] as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(1);
  });

  it('якщо сервер відмовив — картка повертається', async () => {
    server.use(http.post(apiPath('/me/changes/:id/confirm'), () => problem({ status: 409, detail: 'На цей запит уже відповіли.' })));
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const requests = await screen.findAllByRole('article', { name: /^Запит на заміну/ });
    await user.click(within(requests[0] as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    await waitFor(() => expect(screen.getAllByRole('article', { name: /^Запит на заміну/ })).toHaveLength(2));
  });

  it('призначена заміна (NOTIFY): «Добре» чи «Не можу», без строку відповіді', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const [notice] = await screen.findAllByRole('article', { name: /^Нова заміна/ });
    const card = notice as HTMLElement;
    expect(within(card).queryByText(/відповісти до/)).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Не можу' })).toBeInTheDocument();
    await user.click(within(card).getByRole('button', { name: 'Добре' }));
    await waitFor(() => expect(screen.queryAllByRole('article', { name: /^Нова заміна/ })).toHaveLength(0));
  });

  it('без запитів — спокійний порожній стан', async () => {
    server.use(http.get(apiPath('/me/changes'), () => Response.json([])));
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    expect(await screen.findByText(/Нових замін немає/)).toBeInTheDocument();
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
    const summary = () => screen.getByText(/навчальних днів/).closest('p')?.textContent ?? '';

    await user.click(day(/четвер, 19/));
    expect(summary()).toContain('навчальних днів: 1');
    await user.click(day(/пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/));
    expect(summary()).toMatch(/19–20\sлистопада/);
    expect(summary()).toContain('навчальних днів: 2');

    await user.click(day(/вівторок, 24/));
    expect(summary()).toContain('навчальних днів: 1');
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

  it('окремі пари: без вибору й з парою, що не збігається, не каже «пар немає», а показує її пари', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    await waitFor(() => expect(preview()).toMatch(/Зачепить 1\sпару:.*Чт, 19\.11 · 3 пара — 4КІ3, НДЕКС/));

    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    expect(preview()).toMatch(/Оберіть пари вище\. За розкладом у ці дні у вас 1\sпара:.*3 пара — 4КІ3/);
    expect(preview()).not.toContain('пар немає');

    await user.click(screen.getByRole('button', { name: '1 пара' }));
    expect(preview()).toMatch(/Обрані пари не зачеплять ваших занять\..*3 пара — 4КІ3/);

    await user.click(screen.getByRole('button', { name: '3 пара' }));
    expect(preview()).toMatch(/Зачепить 1\sпару:.*3 пара/);
  });

  it('поки вантажиться новий діапазон, не показує пари попереднього', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={nepyivoda.id} today="2026-11-09" />);
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    await waitFor(() => expect(preview()).toContain('Чт, 19.11 · 3 пара'));
    await user.click(screen.getByRole('button', { name: /четвер, 19/ }));
    await user.click(screen.getByRole('button', { name: /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/ }));
    await user.click(screen.getByRole('button', { name: /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/ }));

    expect(screen.getByText(/навчальних днів/).closest('p')?.textContent).toMatch(/^20\sлистопада/);
    expect(preview()).toBe('Перевіряємо ваші пари на ці дні…');
    expect(preview()).not.toContain('19.11');

    releaseFriday();
    await waitFor(() => expect(preview()).toBe('У ці дні за розкладом у вас пар немає.'));
  });
});

describe('AbsenceList: видалення', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('після видалення зʼявляється сповіщення, хоч рядок зник одразу (оптимістично)', async () => {
    const success = vi.spyOn(notify, 'success');
    // відповідь сервера приходить після того, як рядок уже зник (як у живій мережі)
    server.use(http.delete(apiPath('/me/absences/:id'), async () => {
      await new Promise((resolve) => setTimeout(resolve, 100));
      return new Response(null, { status: 204 });
    }));
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <AbsenceList today="2026-11-09" />
        <ConfirmDialogHost />
      </>,
    );
    const button = await screen.findByRole('button', { name: /^Видалити відсутність / });
    const range = (button.getAttribute('aria-label') ?? '').replace('Видалити відсутність ', '');
    await user.click(button);
    await user.click(await screen.findByRole('button', { name: 'Видалити' }));
    // рядок зник ще до відповіді сервера
    expect(screen.queryByRole('button', { name: /^Видалити відсутність / })).not.toBeInTheDocument();
    expect(success).not.toHaveBeenCalled();

    await waitFor(() => expect(success).toHaveBeenCalledWith('Відсутність видалено', { description: range }));
    expect(range).not.toBe('');
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

  it('повідомлення: «Добре» лишає статус, «Не можу» відхиляє неопубліковану й лишає опубліковану', () => {
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
