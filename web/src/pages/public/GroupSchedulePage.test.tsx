import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetMockStores } from '@/mocks/store';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import GroupSchedulePage from './GroupSchedulePage';

function renderGroup(path = '/g/4КІ1?view=week&date=2026-11-11') {
  return renderWithProviders(
    <Routes>
      <Route path="/g/:code" element={<GroupSchedulePage />} />
    </Routes>,
    { route: path },
  );
}

beforeEach(() => {
  localStorage.clear();
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
  // «зараз» — середа того самого тижня: гостю розклад відкритий лише за останні дні, тож справжня дата запуску
  // тестів за кілька тижнів зробила б тиждень 11.11 «архівом»
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-11T08:00:00Z'));
});

afterEach(() => vi.useRealTimers());

describe('Сторінка групи', () => {
  it('має шлях назад, код групи заголовком і без «1 зміна», що читалося як «одна заміна»', async () => {
    renderGroup();
    expect(await screen.findByRole('heading', { level: 1, name: /4КІ1\s*—\s*розклад групи/})).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Назад: Розклад' })).toHaveAttribute('href', '/');
    expect(screen.queryByText(/\d зміна$/)).not.toBeInTheDocument();
  });

  it('другорядне — в одному «⋯»: обране додається звідти, а там же його можна прибрати', async () => {
    const user = userEvent.setup();
    renderGroup();
    await screen.findByRole('heading', { level: 1, name: /4КІ1/ });
    // у заголовку лише два керування й «⋯», без ряду кнопок «Поділитися / У календар / ☆»
    expect(screen.queryByRole('button', { name: /Поділитися/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    for (const name of ['Поділитися', 'Копіювати посилання', 'Друкувати тиждень', 'QR-код для дверей']) {
      expect(screen.getByRole('menuitem', { name })).toBeInTheDocument();
    }
    await user.click(screen.getByRole('menuitem', { name: 'Додати в обране' }));
    expect(useFavoritesStore.getState().favorites.map((f) => f.label)).toEqual(['4КІ1']);

    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    expect(screen.getByRole('menuitem', { name: 'Прибрати з обраного' })).toBeInTheDocument();
  });

  it('підгрупа — у «⋯», а не третім перемикачем; вибір видно тихим рядком і він памʼятається', async () => {
    const user = userEvent.setup();
    const { unmount } = renderGroup();
    await screen.findByRole('heading', { level: 1, name: /4КІ1/ });
    expect(screen.queryByRole('radiogroup', { name: 'Підгрупа' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Лише моя підгрупа…' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Підгрупа 4КІ1' })).getByRole('button', { name: '1 підгрупа' }));

    expect(await screen.findByText('1 підгрупа')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '1 підгрупа — змінити' })).toBeInTheDocument();
    unmount();

    // наступного разу та сама група відкривається вже з підгрупою, хоча в адресі її немає
    renderGroup();
    expect(await screen.findByRole('button', { name: '1 підгрупа — змінити' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '1 підгрупа — змінити' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Уся група' }));
    expect(screen.queryByRole('button', { name: /підгрупа — змінити/ })).not.toBeInTheDocument();
  });

  describe('тиждень, у якому є сьогодні', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      resetMockStores();
    });
    afterEach(() => vi.useRealTimers());

    it('рядок контексту каже стан дня: яка пара зараз і скільки лишилося', async () => {
      vi.setSystemTime(new Date('2026-11-12T10:32:00Z')); // 12:32 у Києві, 4 пара (12:10–13:10)
      renderGroup('/g/4КІ1?view=week&date=2026-11-12');
      // «4 пара», як у герої головної, а не «4-та пара»
      expect(await screen.findByText(/^Зараз 4 пара · ще 38/)).toBeInTheDocument();
    });

    it('після пар — коли вони закінчилися, а не тиша', async () => {
      vi.setSystemTime(new Date('2026-11-12T14:00:00Z')); // 16:00 у Києві
      renderGroup('/g/4КІ1?view=week&date=2026-11-12');
      expect(await screen.findByText(/^Пари закінчилися о/)).toBeInTheDocument();
    });

    it('в іншому тижні стану дня немає', async () => {
      vi.setSystemTime(new Date('2026-11-12T10:32:00Z'));
      renderGroup('/g/4КІ1?view=week&date=2026-11-18');
      await screen.findByRole('heading', { level: 1, name: /4КІ1/ });
      expect(screen.queryByText(/^Зараз \d/)).not.toBeInTheDocument();
    });
  });
});
