import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { paths } from '@/app/routes';
import { mockId } from '@/mocks/ids';
import HomePage from '@/pages/public/HomePage';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import { repairSavedTargets, resolveTarget, type Catalogs } from './saved-targets';

const group = (id: string, code: string): ScheduleTarget => ({ type: 'GROUP', id, label: code, url: `/g/${code}` });
const teacher = (id: string, label: string): ScheduleTarget => ({ type: 'TEACHER', id, label, url: `/t/${id}` });

const catalogs: Catalogs = {
  GROUP: [
    { id: 'g-new', label: '4КІ1', url: '/g/4КІ1' },
    { id: 'g-ve', label: '1ВЕ', url: '/g/1ВЕ' },
  ],
  TEACHER: [{ id: 't-new', label: 'Кренцін Микола Д.', url: '/t/t-new' }],
};

describe('збережені цілі після перевантаження бази', () => {
  it('жива ціль лишається, стара переходить на новий id за підписом, зникла — забувається', () => {
    expect(resolveTarget(group('g-ve', '1ВЕ'), catalogs)).toEqual(group('g-ve', '1ВЕ'));
    expect(resolveTarget(group('g-old', '4КІ1'), catalogs)).toEqual(group('g-new', '4КІ1'));
    expect(resolveTarget(teacher('t-old', 'Кренцін Микола Д.'), catalogs)).toEqual({ ...teacher('t-new', 'Кренцін Микола Д.') });
    expect(resolveTarget(group('g-gone', '9ЗЗ9'), catalogs)).toBeNull();
  });

  it('довідник ще не завантажився чи порожній — нічого не чіпаємо', () => {
    const room: ScheduleTarget = { type: 'ROOM', id: 'r', label: 'ауд. 14', url: '/r/r' };
    expect(resolveTarget(room, catalogs)).toBe(room);
    expect(resolveTarget(group('g-old', '4КІ1'), { GROUP: [] })).toEqual(group('g-old', '4КІ1'));
  });

  it('виправляє останню, нещодавні й обране; стара й нова копія зливаються; без змін — null', () => {
    const repaired = repairSavedTargets(
      {
        lastViewed: group('g-old', '4КІ1'),
        recent: [group('g-old', '4КІ1'), group('g-new', '4КІ1'), group('g-gone', '9ЗЗ9'), group('g-ve', '1ВЕ')],
        favorites: [group('g-gone', '9ЗЗ9')],
      },
      catalogs,
    );
    expect(repaired).toEqual({
      lastViewed: group('g-new', '4КІ1'),
      recent: [group('g-new', '4КІ1'), group('g-ve', '1ВЕ')],
      favorites: [],
    });
    expect(repairSavedTargets(repaired!, catalogs)).toBeNull();
  });
});

describe('Головна: збережена група зникла з бази', () => {
  beforeEach(() => {
    localStorage.clear();
    useFavoritesStore.setState({ favorites: [], recent: [], lastViewed: null });
  });

  it('не показує помилку сервера з id: група переходить на новий id, і головна відкриває її день', async () => {
    const stale = group('01a1ba8a-37e0-7a33-a23d-a3bc7b16389f', '4КІ1');
    useFavoritesStore.setState({ lastViewed: stale, recent: [stale], favorites: [] });
    renderWithProviders(<HomePage />);
    await waitFor(() => expect(useFavoritesStore.getState().lastViewed?.id).toBe(mockId('group', '4КІ1')));
    expect(await screen.findByRole('heading', { name: /^4КІ1 / })).toBeInTheDocument();
    expect(screen.queryByText(/ідентифікатором|не знайдено/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Спробувати ще раз' })).not.toBeInTheDocument();
    expect(useFavoritesStore.getState().recent).toEqual([{ ...group(mockId('group', '4КІ1'), '4КІ1'), url: paths.group('4КІ1') }]);
  });

  it('групи з таким кодом більше немає — головна просто без неї, як для нового відвідувача', async () => {
    const gone = group('01a1ba8a-0000-7a33-a23d-a3bc7b16389f', '9ЗЗ9');
    useFavoritesStore.setState({ lastViewed: gone, recent: [gone], favorites: [] });
    renderWithProviders(<HomePage />);
    await waitFor(() => expect(useFavoritesStore.getState().lastViewed).toBeNull());
    expect(useFavoritesStore.getState().recent).toEqual([]);
    expect(await screen.findByRole('heading', { name: 'Знайти розклад' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/ідентифікатором|9ЗЗ9/)).not.toBeInTheDocument();
  });
});
