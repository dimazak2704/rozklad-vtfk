import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
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
  useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
});

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
});
