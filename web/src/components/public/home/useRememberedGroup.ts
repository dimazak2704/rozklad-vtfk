import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';

/**
 * Група, яку людина відкривала останньою («моя група» для головної). Береться з нещодавніх, а не лише з
 * `lastViewed`: один погляд на розклад викладача не має забирати з головної розклад своєї групи.
 */
export function useRememberedGroup(): ScheduleTarget | null {
  const lastViewed = useFavoritesStore((state) => state.lastViewed);
  const recent = useFavoritesStore((state) => state.recent);
  const favorites = useFavoritesStore((state) => state.favorites);
  if (lastViewed?.type === 'GROUP') return lastViewed;
  return recent.find((target) => target.type === 'GROUP') ?? favorites.find((target) => target.type === 'GROUP') ?? null;
}
