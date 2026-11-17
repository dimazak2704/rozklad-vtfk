import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { DayDto, LessonView, Weekday } from '@/api/types';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { AbsenceForm } from './AbsenceForm';

const krentsin = teacherNamed('Кренцін');
const FRIDAY_20 = /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/;

/** Тіла POST /me/absences; відповідь — 422, щоб форма лишилась як є. */
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

describe('AbsenceForm: причина «Інше»', () => {
  beforeEach(() => window.localStorage.removeItem('rozklad.absenceReason'));

  it('«Інше» мишею: поле коментаря переїжджає під варіанти як «Що саме?» і отримує фокус; текст іде в comment', async () => {
    const bodies = trackAbsences();
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={krentsin.id} today="2026-11-20" />);
    expect(screen.getByRole('textbox', { name: /^Коментар для диспетчера/ })).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Інше' }));
    const what = screen.getByRole('textbox', { name: 'Що саме? (необовʼязково)' });
    // одне поле на одні дані: коментаря внизу більше немає, а «Що саме?» — усередині групи «Причина»
    expect(screen.queryByRole('textbox', { name: /^Коментар для диспетчера/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
    expect(within(screen.getByRole('group', { name: 'Причина' })).getByRole('textbox')).toBe(what);
    expect(what).toHaveFocus();

    await user.type(what, '  Курси підвищення кваліфікації ');
    await user.click(screen.getByRole('button', { name: 'Повідомити диспетчера' }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ reasonCategory: 'OTHER', comment: 'Курси підвищення кваліфікації', dateFrom: '2026-11-20', dateTo: '2026-11-20' });
  });

  it('текст не губиться, коли причину змінили: він знову в «Коментар для диспетчера»', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={krentsin.id} today="2026-11-20" />);
    await user.click(screen.getByRole('radio', { name: 'Інше' }));
    await user.type(screen.getByRole('textbox', { name: /^Що саме\?/ }), 'Конференція');

    await user.click(screen.getByRole('radio', { name: 'Відрядження' }));
    expect(screen.getByRole('textbox', { name: /^Коментар для диспетчера/ })).toHaveValue('Конференція');
    await user.click(screen.getByRole('radio', { name: 'Інше' }));
    expect(screen.getByRole('textbox', { name: /^Що саме\?/ })).toHaveValue('Конференція');
  });

  it('стрілки клавіатури по причинах не перестрибують у поле: фокус лишається на варіанті', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={krentsin.id} today="2026-11-20" />);
    const personal = screen.getByRole('radio', { name: 'Особисті обставини' });
    await user.click(personal);
    // Radix обирає варіант, коли фокус приходить (у таймері) ще з натиснутою стрілкою — тримаємо її до того
    await user.keyboard('{ArrowDown>}');
    const other = screen.getByRole('radio', { name: 'Інше' });
    await waitFor(() => expect(other).toBeChecked());
    await user.keyboard('{/ArrowDown}');
    expect(other).toHaveFocus();
    expect(screen.getByRole('textbox', { name: /^Що саме\?/ })).not.toHaveFocus();
    // поле — наступне за Tab
    await user.tab();
    expect(screen.getByRole('textbox', { name: /^Що саме\?/ })).toHaveFocus();
  });

  it('«Інше», запамʼятане з минулого разу, показує «Що саме?» одразу, але фокус не краде', () => {
    window.localStorage.setItem('rozklad.absenceReason', 'OTHER');
    renderWithProviders(<AbsenceForm teacherId={krentsin.id} today="2026-11-20" />);
    const what = screen.getByRole('textbox', { name: /^Що саме\?/ });
    expect(what).not.toHaveFocus();
    expect(screen.getByRole('radio', { name: 'Інше' })).toBeChecked();
  });
});

describe('AbsenceForm: окремі пари на кілька днів', () => {
  const lesson = (id: string, pair: number, startsAt: string, group: string): LessonView => ({
    id,
    pair,
    startsAt,
    endsAt: '00:00',
    discipline: { id: '00000000-0000-4000-8000-0000000000d2', shortName: 'СТП', name: 'СТП', color: null },
    teacher: { id: krentsin.id, shortName: 'Кренцін М.Д.', displayName: 'Кренцін М.Д.' },
    room: null,
    group: { id: `00000000-0000-4000-8000-0000000000${group === '4КІ1' ? 'b1' : 'b2'}`, code: group, course: 4 },
    subgroup: 0,
    kind: 'REGULAR',
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
  });
  const day = (date: string, weekday: Weekday, lessons: LessonView[]): DayDto => ({
    date,
    weekday,
    weekdayLabel: '',
    parity: 'NUMERATOR',
    studyDay: true,
    note: null,
    lessons,
  });

  beforeEach(() => {
    // пт 20.11: 3 пара — 4КІ1; пн 23.11: 3 пара — 4КІ2 і 5 пара — 4КІ1
    const all = [
      day('2026-11-20', 5, [lesson('00000000-0000-4000-8000-0000000000c1', 3, '11:20', '4КІ1')]),
      day('2026-11-23', 1, [lesson('00000000-0000-4000-8000-0000000000c2', 3, '11:20', '4КІ2'), lesson('00000000-0000-4000-8000-0000000000c3', 5, '14:40', '4КІ1')]),
    ];
    server.use(
      http.get(apiPath('/me/schedule'), ({ request }) => {
        const url = new URL(request.url);
        const from = url.searchParams.get('from') ?? '';
        const to = url.searchParams.get('to') ?? '';
        const days = all.filter((d) => d.date >= from && d.date <= to);
        return Response.json({ owner: { type: 'TEACHER', id: krentsin.id, label: 'Кренцін М.Д.' }, from, to, days });
      }),
    );
  });

  it('пара за номером — одна на весь діапазон: підпис перелічує кожен день, позначене не губиться, коли діапазон росте', async () => {
    const bodies = trackAbsences();
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={krentsin.id} today="2026-11-20" />);
    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    // перший клік — лише пʼятниця
    await user.click(screen.getByRole('button', { name: FRIDAY_20 }));
    const friday3 = await screen.findByRole('checkbox', { name: /^3 пара · 11:20\s*·\s*4КІ1, СТП$/ });
    await user.click(friday3);
    expect(screen.queryByText('Позначена пара — в усі обрані дні, де вона у вас є.')).not.toBeInTheDocument();

    // другий клік — до понеділка: 3 пара тепер про обидва дні, і вона лишилась позначеною
    await user.click(screen.getByRole('button', { name: /понеділок, 23/ }));
    const pair3 = await screen.findByRole('checkbox', { name: /^3 пара · 11:20$/ });
    expect(pair3).toBeChecked();
    expect(pair3).toHaveAccessibleDescription('Пт, 20.11 — 4КІ1, СТП; Пн, 23.11 — 4КІ2, СТП');
    const pair5 = screen.getByRole('checkbox', { name: /^5 пара · 14:40$/ });
    expect(pair5).not.toBeChecked();
    expect(pair5).toHaveAccessibleDescription('Пн, 23.11 — 4КІ1, СТП');
    expect(screen.getByText('Позначена пара — в усі обрані дні, де вона у вас є.')).toBeInTheDocument();
    // «Інша пара…» закрита: позначене є серед пар списку
    expect(screen.getByRole('button', { name: 'Інша пара…' })).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Лікарняний' }));
    await user.click(screen.getByRole('button', { name: 'Повідомити диспетчера' }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ dateFrom: '2026-11-20', dateTo: '2026-11-23', pairs: [3], reasonCategory: 'SICK_LEAVE' });
  });

  it('пара, якої в нових днях немає, лишається видно позначеною в «Інша пара», а не зникає мовчки', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AbsenceForm teacherId={krentsin.id} today="2026-11-20" />);
    await user.click(screen.getByRole('radio', { name: 'Окремі пари' }));
    await user.click(screen.getByRole('button', { name: /понеділок, 23/ }));
    await user.click(await screen.findByRole('checkbox', { name: /^5 пара/ }));

    // новий вибір — лише пʼятниця, де 5 пари немає
    await user.click(screen.getByRole('button', { name: /понеділок, 23/ }));
    await user.click(screen.getByRole('button', { name: /вівторок, 24/ }));
    await user.click(screen.getByRole('button', { name: FRIDAY_20 }));
    await user.click(screen.getByRole('button', { name: FRIDAY_20 }));
    await screen.findByRole('checkbox', { name: /^3 пара/ });
    expect(screen.getByRole('button', { name: '5 пара' })).toHaveAttribute('aria-pressed', 'true');
  });
});
