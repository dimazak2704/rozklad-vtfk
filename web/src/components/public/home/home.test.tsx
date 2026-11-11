import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import HomePage from '@/pages/public/HomePage';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import { GroupsDirectory } from './GroupsDirectory';
import { TodayChanges } from './TodayChanges';

beforeEach(() => {
  localStorage.clear();
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
});

describe('«Усі групи» на головній', () => {
  it('закрито — одне речення; відкрито — коди посиланнями; вибір памʼятається', async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<GroupsDirectory />);
    expect(await screen.findByText(/груп(и|а)?, (один|два|три|чотири|пʼять|шість) курс/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '4КІ1' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Відкрити список' }));
    expect(screen.getByRole('link', { name: '4КІ1' })).toHaveAttribute('href', expect.stringContaining('/g/'));
    unmount();

    // наступного разу список уже відкритий
    renderWithProviders(<GroupsDirectory />);
    expect(await screen.findByRole('link', { name: '4КІ1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Згорнути список' })).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('«Заміни» на головній', () => {
  it('каже реченням, скільки замін, і веде до своєї групи', async () => {
    server.use(
      http.get(apiPath('/public/changes'), () =>
        HttpResponse.json({
          date: '2026-11-11',
          parity: 'NUMERATOR',
          studyDay: true,
          weekdayLabel: 'Середа',
          order: { id: 'order-52', number: 52, publishedAt: '2026-11-10T12:20:00Z', imported: false },
          amendments: [],
          groups: [
            {
              group: { id: 'g-4ki1', code: '4КІ1', course: 4 },
              items: [
                { pairLabel: '1', rowType: 'REPLACEMENT', from: { discipline: 'ДК', teacher: 'Паламарчук' }, reasonText: 'Відп.', to: { discipline: 'ДК', teacher: 'Резнік' }, room: '203', subgroupNote: null },
              ],
            },
            {
              group: { id: 'g-1ki2', code: '1КІ2', course: 1 },
              items: [
                { pairLabel: '3', rowType: 'CANCELLATION', from: { discipline: 'Матем.', teacher: 'Вихристюк' }, reasonText: 'Відп.', to: { discipline: null, teacher: null }, room: null, subgroupNote: null },
              ],
            },
          ],
        }),
      ),
    );
    useFavoritesStore.setState({ favorites: [], recent: [{ type: 'GROUP', id: 'g-4ki1', label: '4КІ1', url: '/g/4КІ1' }], lastViewed: null });
    const { container } = renderWithProviders(<TodayChanges />);
    expect(await screen.findByRole('link', { name: '4КІ1' })).toHaveAttribute('href', expect.stringContaining('view=day'));
    expect(container.textContent).toMatch(/2\sзаміни у 2\sгрупах, серед них у 4КІ1\./);
    // жодних табличок з кодами всіх груп
    expect(screen.queryByRole('link', { name: /1КІ2/ })).not.toBeInTheDocument();
  });
});

describe('Головна без сервера', () => {
  it('одна помилка на сторінку з однією кнопкою, а не чотири однакові', async () => {
    server.use(http.get(apiPath('/public/*'), () => problem({ status: 503, detail: 'Сервіс недоступний.' })));
    renderWithProviders(<HomePage />);
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Спробувати ще раз' })).toHaveLength(1));
    expect(screen.getByRole('alert')).toHaveTextContent('Розклад зараз не завантажується');
    // пошук лишається — ним можна скористатися, щойно сервер оживе
    expect(screen.getByRole('combobox', { name: /Знайти розклад/ })).toBeInTheDocument();
  });
});
