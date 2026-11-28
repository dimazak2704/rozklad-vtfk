import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import GroupSchedulePage from '@/pages/public/GroupSchedulePage';
import TeacherSchedulePage from '@/pages/public/TeacherSchedulePage';

function renderSchedule(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/g/:code" element={<GroupSchedulePage />} />
      <Route path="/t/:id" element={<TeacherSchedulePage />} />
    </Routes>,
    { route: path },
  );
}

/** Відкрити картку першої пари з клавіатури (так само, як Enter на клітинці). */
async function openFirstCard(user: ReturnType<typeof userEvent.setup>) {
  // клітинки зʼявляються після завантаження розкладу (під навантаженням повного прогону — не одразу)
  const cell = await waitFor(
    () => {
      const found = document.querySelector<HTMLElement>('.lesson-cell');
      if (!found) throw new Error('розклад ще не намальовано');
      return found;
    },
    { timeout: 5000 },
  );
  act(() => cell.focus());
  await user.keyboard('{Enter}');
  return screen.findByRole('dialog', { name: /^Деталі:/ }, { timeout: 5000 });
}

beforeEach(() => {
  localStorage.clear();
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-11T08:00:00Z'));
});

afterEach(() => vi.useRealTimers());

describe('Картка заняття на сторінках розкладу', () => {
  it('на сторінці групи: викладач і аудиторія — посилання з тим самим поданням і днем, код групи — текст', async () => {
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=week&date=2026-11-12');
    const card = await openFirstCard(user);

    const teacher = within(card).getByRole('link', { name: /розклад викладача/ });
    expect(teacher.getAttribute('href')).toMatch(/^\/t\/[^?]+\?view=week&date=2026-11-12$/);
    const room = within(card).queryByRole('link', { name: /розклад аудиторії/ });
    if (room) expect(room.getAttribute('href')).toMatch(/\?view=week&date=2026-11-12$/);
    expect(within(card).queryByRole('link', { name: /розклад групи/ })).not.toBeInTheDocument();
    expect(within(card).getByText('4КІ1').closest('a')).toBeNull();
    // окремого рядка «Розклад викладача · Аудиторія …» більше немає
    expect(within(card).queryByText('Розклад викладача')).not.toBeInTheDocument();
  });

  it('сьогоднішній день у переходах не пишеться, як і на самій сторінці', async () => {
    const user = userEvent.setup();
    renderSchedule('/g/4КІ1?view=day');
    const card = await openFirstCard(user);
    expect(within(card).getByRole('link', { name: /розклад викладача/ }).getAttribute('href')).toMatch(/\?view=day$/);
  });

  it('на сторінці викладача його імʼя — текст, а група — посилання на її розклад', async () => {
    const user = userEvent.setup();
    const first = renderSchedule('/g/4КІ1?view=week&date=2026-11-12');
    const groupCard = await openFirstCard(user);
    const href = within(groupCard).getByRole('link', { name: /розклад викладача/ }).getAttribute('href')!;
    first.unmount();

    renderSchedule(href);
    const card = await openFirstCard(user);
    expect(within(card).queryByRole('link', { name: /розклад викладача/ })).not.toBeInTheDocument();
    const group = within(card).getByRole('link', { name: /розклад групи/ });
    expect(group.getAttribute('href')).toMatch(/^\/g\/[^?]+\?view=week&date=2026-11-12$/);
  });
});

describe('Рядок контексту в заголовку', () => {
  it('тримає висоту рядка «N замін» ще до завантаження розкладу, щоб тиждень не підстрибував', async () => {
    renderSchedule('/g/4КІ1?view=week&date=2026-11-12');
    const reserve = () => document.querySelector('header [data-slot="meta-reserve"]');
    // заголовок зʼявляється разом із рядком-резервом, ще до клітинок тижня
    await waitFor(() => expect(reserve()).toHaveClass('min-h-8'));
    await waitFor(() => expect(document.querySelector('.lesson-cell')).not.toBeNull(), { timeout: 5000 });
    expect(reserve()).toHaveClass('min-h-8');
  });
});
