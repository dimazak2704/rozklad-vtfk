import { useEffect, useMemo } from 'react';
import { usePublicGroups, usePublicRooms, usePublicTeachers } from '@/api/public';
import { useFavoritesStore } from '@/stores/favorites';
import { groupItems, roomItems, teacherItems } from './finder-items';
import { repairSavedTargets, type Catalogs } from './saved-targets';

/**
 * Головна звіряє збережені цілі з довідниками (ті самі запити, що й у пошуку): після перевантаження бази стара
 * група переходить на новий id, а зникла — забувається. Довідник, що не завантажився, нічого не змінює.
 */
export function useRepairSavedTargets() {
  const groups = usePublicGroups();
  const teachers = usePublicTeachers();
  const rooms = usePublicRooms();
  const catalogs = useMemo<Catalogs>(
    () => ({
      GROUP: groups.isSuccess ? groupItems(groups.data) : undefined,
      TEACHER: teachers.isSuccess ? teacherItems(teachers.data) : undefined,
      ROOM: rooms.isSuccess ? roomItems(rooms.data) : undefined,
    }),
    [groups.isSuccess, groups.data, teachers.isSuccess, teachers.data, rooms.isSuccess, rooms.data],
  );

  useEffect(() => {
    const state = useFavoritesStore.getState();
    const repaired = repairSavedTargets(state, catalogs);
    if (repaired) useFavoritesStore.setState(repaired);
  }, [catalogs]);
}
