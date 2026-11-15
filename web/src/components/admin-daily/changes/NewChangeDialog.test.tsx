import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CalendarSemester } from '@/api/calendar';
import { api } from '@/api/client';
import type { LoadItem } from '@/api/load';
import type { ChangeDto, CreateChangeRequest } from '@/api/types';
import { replyToChange } from '@/mocks/handlers/admin-daily/change-commands';
import { DISCIPLINES, GROUPS } from '@/mocks/fixtures';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';
import ChangesPage from '@/pages/admin/ChangesPage';
import { renderWithProviders } from '@/test/render';
import { formatTeacherName } from '@/lib/teacher-name';

const NOW = new Date('2026-11-09T05:40:00Z');
const WAIT = { timeout: 8_000 };
vi.setConfig({ testTimeout: 30_000 });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  resetMockStores();
});
afterEach(() => vi.useRealTimers());

const group = GROUPS.find((g) => g.code === '4КІ1') as (typeof GROUPS)[number];
const discipline = DISCIPLINES.find((d) => d.shortName === 'НДЕКСМ') as (typeof DISCIPLINES)[number];

describe('«Нова зміна» — зміна без відсутності', () => {
  it('з «Обліку годин» («Додати 2 пари»): форма додаткової пари для цієї групи й дисципліни, викладач — з навантаження', async () => {
    const semester = (await api.get<CalendarSemester[]>('/admin/semesters')).find((s) => s.active) as CalendarSemester;
    const load = await api.get<LoadItem[]>('/admin/load', {
      semesterId: semester.id,
      groupId: group.id,
    });
    const teacher = load.find((item) => item.discipline.id === discipline.id)?.teachers[0]?.teacher;
    expect(teacher).toBeDefined();

    let posted: CreateChangeRequest | null = null;
    server.use(
      http.post(apiPath('/admin/changes'), async ({ request }) => {
        posted = (await request.clone().json()) as CreateChangeRequest;
      }),
    );

    const user = userEvent.setup({
      advanceTimers: (ms) => vi.advanceTimersByTime(ms),
    });
    renderWithProviders(<ChangesPage />, {
      route: `/admin/changes?date=2026-11-09&group=${group.id}&discipline=${discipline.id}&suggest=ADDITIONAL`,
    });
    const dialog = await screen.findByRole('dialog', { name: 'Нова зміна' }, WAIT);
    // не перша-ліпша відсутність, а саме ця група й дисципліна
    expect(within(dialog).getByRole('radio', { name: 'Додаткова пара' })).toBeChecked();
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: /Група/ })).toHaveTextContent('4КІ1'), WAIT);
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: /Дисципліна/ })).toHaveTextContent('НДЕКСМ'), WAIT);
    await waitFor(
      () =>
        expect(within(dialog).getByRole('combobox', { name: /Викладач/ })).toHaveTextContent(
          formatTeacherName(teacher!, 'grid').split(' ')[0] as string,
        ),
      WAIT,
    );

    // пара не обрана — підказка, запиту немає
    await user.click(within(dialog).getByRole('button', { name: 'Додати пару' }));
    expect(await within(dialog).findByText('Оберіть пару')).toBeInTheDocument();
    expect(posted).toBeNull();

    await user.click(within(dialog).getByRole('button', { name: /^5 пара/ }));
    await user.click(within(dialog).getByRole('button', { name: /^6 пара/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Додати пару' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Нова зміна' })).not.toBeInTheDocument(), WAIT);
    expect(posted).toMatchObject({
      kind: 'ADDITIONAL',
      date: '2026-11-09',
      pairFrom: 5,
      pairTo: 6,
      groupId: group.id,
      newDisciplineId: discipline.id,
      newTeacherId: teacher!.id,
      absenceId: null,
    });
    // створена пара видно на сторінці, а не лише в розпорядженні
    expect(await screen.findByRole('heading', { name: 'Інші зміни' }, WAIT)).toBeInTheDocument();
    // нерозривний пробіл тримає «5–6 пари» разом; getByText нормалізує його до звичайного
    expect(screen.getByText('4КІ1 · 5–6 пари')).toBeInTheDocument();
  });

  it('кнопка «Нова зміна» й клавіша C відкривають ту саму форму; без пропозиції — порожня', async () => {
    const user = userEvent.setup({
      advanceTimers: (ms) => vi.advanceTimersByTime(ms),
    });
    renderWithProviders(<ChangesPage />, {
      route: '/admin/changes?date=2026-11-09',
    });
    await screen.findByRole('button', { name: 'Нова зміна' }, WAIT);
    await user.keyboard('c');
    const dialog = await screen.findByRole('dialog', { name: 'Нова зміна' });
    expect(within(dialog).getByRole('combobox', { name: /Група/ })).toHaveTextContent('Оберіть групу');
    await user.click(within(dialog).getByRole('radio', { name: 'Інша аудиторія' }));
    expect(within(dialog).getByText('Оберіть групу й дату — тут зʼявляться її пари.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Скасувати' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Нова зміна' })).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Нова зміна' }));
    expect(await screen.findByRole('dialog', { name: 'Нова зміна' })).toBeInTheDocument();
  });

  it('на телефоні «⋯» має «Нову зміну» й «Розпорядження» — кожну один раз', async () => {
    // у jsdom matchMedia нічого не збігає — сторінка вважає екран вузьким, як на 375
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    renderWithProviders(<ChangesPage />, { route: '/admin/changes?date=2026-11-09' });
    await user.click(await screen.findByRole('button', { name: 'Ще дії' }, WAIT));
    const items = (await screen.findAllByRole('menuitem')).map((item) => item.textContent);
    expect(items.filter((text) => text === 'Нова зміна')).toHaveLength(1);
    expect(items.filter((text) => text === 'Розпорядження')).toHaveLength(1);
    expect(new Set(items).size).toBe(items.length);
  });

  it('викладач відмовився від додаткової пари: вона лишається з «відмова», а «Інший викладач…» ставить ту саму пару іншому', async () => {
    const semester = (await api.get<CalendarSemester[]>('/admin/semesters')).find((s) => s.active) as CalendarSemester;
    const load = await api.get<LoadItem[]>('/admin/load', { semesterId: semester.id, groupId: group.id });
    const teacher = load.find((item) => item.discipline.id === discipline.id)?.teachers[0]?.teacher;
    expect(teacher).toBeDefined();
    const body: CreateChangeRequest = {
      kind: 'ADDITIONAL',
      date: '2026-11-09',
      pairFrom: 6,
      pairTo: 6,
      groupId: group.id,
      subgroup: 0,
      originalLessonId: null,
      newDisciplineId: discipline.id,
      newTeacherId: teacher!.id,
      newRoomId: null,
      reasonCode: 'OTHER',
      reasonText: 'Додаткова пара',
      absenceId: null,
    } as unknown as CreateChangeRequest;
    const declined = await api.post<ChangeDto>('/admin/changes', body);
    replyToChange(declined.id, teacher!.id, 'DECLINE', null, new Date());

    let posted: CreateChangeRequest | null = null;
    const cancelled: string[] = [];
    server.use(
      http.post(apiPath('/admin/changes'), async ({ request }) => {
        posted = (await request.clone().json()) as CreateChangeRequest;
      }),
      http.delete(apiPath('/admin/changes/:id'), ({ params }) => {
        cancelled.push(String(params.id));
      }),
    );

    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    renderWithProviders(<ChangesPage />, { route: '/admin/changes?date=2026-11-09' });
    const others = (await screen.findByRole('heading', { name: 'Інші зміни' }, WAIT)).closest('section') as HTMLElement;
    expect(within(others).getByText('4КІ1 · 6 пара')).toBeInTheDocument();
    expect(within(others).getByText('відмова')).toBeInTheDocument();

    await user.click(within(others).getByRole('button', { name: 'Інший викладач…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Нова зміна' }, WAIT);
    const declinedName = formatTeacherName(teacher!, 'grid').split(' ')[0] as string;
    expect(within(dialog).getByRole('note')).toHaveTextContent('не може провести цю пару');
    // та сама група, дисципліна й пара; викладача, що відмовився, не підставляємо
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: /Група/ })).toHaveTextContent('4КІ1'), WAIT);
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: /Дисципліна/ })).toHaveTextContent('НДЕКСМ'), WAIT);
    expect(within(dialog).getByRole('button', { name: /^6 пара/ })).toHaveAttribute('aria-pressed', 'true');
    const teacherBox = within(dialog).getByRole('combobox', { name: /Викладач/ });
    expect(teacherBox).not.toHaveTextContent(declinedName);

    await user.click(teacherBox);
    const options = await screen.findAllByRole('option');
    const other = options.find((option) => !option.textContent?.includes(declinedName)) as HTMLElement;
    await user.click(other);
    await user.click(within(dialog).getByRole('button', { name: 'Додати пару' }));
    await waitFor(() => expect(cancelled).toEqual([declined.id]), WAIT);
    expect(posted).toMatchObject({ kind: 'ADDITIONAL', date: '2026-11-09', pairFrom: 6, pairTo: 6, groupId: group.id, newDisciplineId: discipline.id });
    expect((posted as CreateChangeRequest | null)?.newTeacherId).not.toBe(teacher!.id);
  });

  it('з «Обліку годин» у неділю: дата — понеділок, видно, скільки пар бракує і які пари в групи вже є', async () => {
    renderWithProviders(<ChangesPage />, {
      // неділя 29.11: 16–27.11 у 4КІ1 тижні НС без пар, тож беремо понеділок після них
      route: `/admin/changes?date=2026-11-29&group=${group.id}&discipline=${discipline.id}&suggest=ADDITIONAL&lessons=2`,
    });
    const dialog = await screen.findByRole('dialog', { name: 'Нова зміна' }, WAIT);
    expect(within(dialog).getByLabelText(/Дата/)).toHaveValue('2026-11-30');
    expect(within(dialog).getByRole('note')).toHaveTextContent(/бракує 2\sпар\./);
    // понеділок 30.11 у 4КІ1 є пари: їхні номери позначені ще до збереження
    await waitFor(() => expect(within(dialog).getAllByRole('button', { name: /у групи вже/ }).length).toBeGreaterThan(0), WAIT);
  });
});
