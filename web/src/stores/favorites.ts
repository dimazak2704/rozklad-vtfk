import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { EntityType, Uuid } from '@/api/types';

/** Те, що людина відкривала: група, викладач або аудиторія. */
export interface ScheduleTarget {
  type: EntityType;
  id: Uuid;
  /** «4КІ1», «Кренцін М.Д.», «ауд. 309» */
  label: string;
  /** Маршрут у застосунку: `/g/4КІ1`, `/t/{id}`, `/r/{id}` */
  url: string;
}

const MAX_RECENT = 8;

interface FavoritesState {
  /** Остання переглянута ціль — головна сторінка відкриває саме її. */
  lastViewed: ScheduleTarget | null;
  recent: ScheduleTarget[];
  favorites: ScheduleTarget[];
  rememberView: (target: ScheduleTarget) => void;
  toggleFavorite: (target: ScheduleTarget) => void;
  isFavorite: (type: EntityType, id: Uuid) => boolean;
  forgetRecent: () => void;
}

const sameTarget = (a: ScheduleTarget, b: Pick<ScheduleTarget, 'type' | 'id'>) => a.type === b.type && a.id === b.id;

export const useFavoritesStore = create<FavoritesState>()(
  persist(
    (set, get) => ({
      lastViewed: null,
      recent: [],
      favorites: [],
      rememberView: (target) =>
        set((state) => ({
          lastViewed: target,
          recent: [target, ...state.recent.filter((item) => !sameTarget(item, target))].slice(0, MAX_RECENT),
        })),
      toggleFavorite: (target) =>
        set((state) => ({
          favorites: state.favorites.some((item) => sameTarget(item, target))
            ? state.favorites.filter((item) => !sameTarget(item, target))
            : [...state.favorites, target],
        })),
      isFavorite: (type, id) => get().favorites.some((item) => sameTarget(item, { type, id })),
      forgetRecent: () => set({ recent: [], lastViewed: null }),
    }),
    {
      name: 'rozklad.favorites',
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({ lastViewed: state.lastViewed, recent: state.recent, favorites: state.favorites }),
      version: 1,
    },
  ),
);
