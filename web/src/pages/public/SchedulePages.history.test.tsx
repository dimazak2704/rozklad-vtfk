import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CurrentUserState } from '@/auth/useCurrentUser';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import GroupSchedulePage from './GroupSchedulePage';
import TeacherSchedulePage from './TeacherSchedulePage';

/** Гість або викладач (персонал); токен шле справжній API-клієнт — його дає getAccessToken. */
const auth = vi.hoisted(() => ({ staff: false }));

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: (): Partial<CurrentUserState> =>
    auth.staff
      ? { status: 'authenticated', isAdmin: false, isTeacher: true, roles: ['TEACHER'] }
      : { status: 'anonymous', isAdmin: false, isTeacher: false, roles: [] },
}));
vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve(auth.staff ? 'teacher-token' : null) }));

/** Четвер, 12.11.2026: гостю відкрито з 5 листопада, тобто розклад — з тижня 2–6 листопада. */
const NOW = new Date('2026-11-12T08:00:00Z');

function renderSchedule(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/g/:code" element={<GroupSchedulePage />} />
      <Route path="/t/:id" element={<TeacherSchedulePage />} />
    </Routes>,
    { route: path },
  );
}

const previousWeek = () => screen.getByRole('button', { name: /^Попередній тиждень/ });

beforeEach(() => {
  auth.staff = false;
  localStorage.clear();
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  server.events.removeAllListeners();
});

describe('Розклад: гість бачить лише останні тижні', () => {
  it('посилання на давній тиждень групи — пояснення замість помилки й шлях до найранішого тижня, де «‹» уже вимкнено', async () => {
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=week&date=2026-10-19');

    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
    expect(screen.getByText(/розклад з тижня 2–6\s+листопада/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/не завантажився/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Найраніший доступний тиждень' }));
    expect(await screen.findByRole('button', { name: /^Обрати тиждень\. Зараз: 2–6\s+листопада/ })).toBeInTheDocument();
    expect(previousWeek()).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByText('Цей тиждень уже в архіві')).not.toBeInTheDocument();
  });

  it('на поточному тижні «‹» веде на тиждень межі, а далі — ні', async () => {
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=week');
    await screen.findByRole('button', { name: /^Обрати тиждень\. Зараз: цей тиждень/ });
    expect(previousWeek()).not.toHaveAttribute('aria-disabled');
    await user.click(previousWeek());
    expect(await screen.findByRole('button', { name: /^Обрати тиждень\. Зараз: 2–6\s+листопада/ })).toBeInTheDocument();
    expect(previousWeek()).toHaveAttribute('aria-disabled', 'true');
    await user.click(previousWeek());
    expect(screen.getByRole('button', { name: /^Обрати тиждень\. Зараз: 2–6\s+листопада/ })).toBeInTheDocument();
  });

  it('викладач: давній тиждень — не «такого викладача немає», а те саме пояснення', async () => {
    renderSchedule(`/t/${teacherNamed('Кренцін').id}?view=week&date=2026-10-19`);
    expect(await screen.findByText('Цей тиждень уже в архіві')).toBeInTheDocument();
    expect(screen.queryByText('Такого викладача в розкладі немає')).not.toBeInTheDocument();
  });
});

describe('Розклад: персонал бачить увесь архів', () => {
  it('давній тиждень відкривається з токеном, «‹» гортає без межі', async () => {
    auth.staff = true;
    const seen: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname.startsWith(apiPath('/public/schedule'))) seen.push(request.headers.get('Authorization') ?? 'none');
    });
    renderSchedule('/g/4КІ1?view=week&date=2026-10-19');

    expect(await screen.findByRole('button', { name: /^Обрати тиждень\. Зараз: 19–23\s+жовтня/ })).toBeInTheDocument();
    expect(await screen.findAllByText('Понеділок')).not.toHaveLength(0);
    expect(screen.queryByText('Цей тиждень уже в архіві')).not.toBeInTheDocument();
    expect(previousWeek()).not.toHaveAttribute('aria-disabled');
    expect(seen.length).toBeGreaterThan(0);
    expect(new Set(seen)).toEqual(new Set(['Bearer teacher-token']));
  });
});
