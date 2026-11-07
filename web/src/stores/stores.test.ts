import { afterEach, describe, expect, it } from 'vitest';
import { matchesCombo } from '@/hooks/useHotkeys';
import { useCommandStore } from './commands';
import { useFavoritesStore, type ScheduleTarget } from './favorites';
import { applyTheme } from './theme';

const target = (id: string, label = id): ScheduleTarget => ({ type: 'GROUP', id, label, url: `/g/${label}` });

describe('favorites', () => {
  afterEach(() => useFavoritesStore.setState({ lastViewed: null, recent: [], favorites: [] }));

  it('памʼятає останню ціль, прибирає дублікати й обмежує історію', () => {
    const { rememberView } = useFavoritesStore.getState();
    for (let i = 0; i < 10; i += 1) rememberView(target(`g${i}`));
    rememberView(target('g3'));
    const state = useFavoritesStore.getState();
    expect(state.lastViewed?.id).toBe('g3');
    expect(state.recent).toHaveLength(8);
    expect(state.recent[0]?.id).toBe('g3');
    expect(state.recent.filter((t) => t.id === 'g3')).toHaveLength(1);
  });

  it('перемикає вибране', () => {
    const { toggleFavorite } = useFavoritesStore.getState();
    toggleFavorite(target('4КІ1'));
    expect(useFavoritesStore.getState().isFavorite('GROUP', '4КІ1')).toBe(true);
    toggleFavorite(target('4КІ1'));
    expect(useFavoritesStore.getState().isFavorite('GROUP', '4КІ1')).toBe(false);
  });

  it('зберігає вибір у localStorage', () => {
    useFavoritesStore.getState().toggleFavorite(target('4КІ2'));
    expect(localStorage.getItem('rozklad.favorites')).toContain('4КІ2');
  });
});

describe('commands', () => {
  it('реєструє й прибирає дії сторінки за областю', () => {
    const store = useCommandStore.getState();
    store.register('page-a', [{ id: 'publish', label: 'Опублікувати', perform: () => undefined }]);
    store.register('page-b', [{ id: 'export', label: 'Експорт', perform: () => undefined }]);
    expect(Object.keys(useCommandStore.getState().commands)).toEqual(['page-a:publish', 'page-b:export']);
    store.unregister('page-a');
    expect(Object.keys(useCommandStore.getState().commands)).toEqual(['page-b:export']);
  });
});

describe('hotkeys', () => {
  const key = (k: string, mods: Partial<Record<'ctrlKey' | 'metaKey' | 'shiftKey' | 'altKey', boolean>> = {}) => ({
    key: k,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    altKey: false,
    ...mods,
  });

  it('розпізнає mod+k, символ «?» і фізичну клавішу в українській розкладці', () => {
    expect(matchesCombo(key('k', { ctrlKey: true }), 'mod+k')).toBe(true);
    expect(matchesCombo(key('k'), 'mod+k')).toBe(false);
    expect(matchesCombo(key('?', { shiftKey: true }), '?')).toBe(true);
    expect(matchesCombo(key('е'), 't', 't')).toBe(true);
    expect(matchesCombo(key('t', { ctrlKey: true }), 't')).toBe(false);
  });
});

describe('theme', () => {
  it('ставить data-theme і color-scheme на <html>', () => {
    applyTheme('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
    applyTheme('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });
});
