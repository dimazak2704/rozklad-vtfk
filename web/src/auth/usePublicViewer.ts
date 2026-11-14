import { useCurrentUser } from './useCurrentUser';

/**
 * Хто дивиться публічні сторінки. Персонал коледжу (увійшов ADMIN чи TEACHER) бачить увесь архів замін,
 * розпоряджень і розкладу; гість — лише останні дні (`publicHistoryFrom` у meta) і майбутнє.
 */
export type PublicViewer = 'staff' | 'guest';

/**
 * `null` — вхід ще визначається (сесію читають зі сховища): запити, відповідь яких залежить від входу,
 * чекають, щоб не спитати сервер від імені гостя і не показати персоналу «архів недоступний».
 */
export function usePublicViewer(): PublicViewer | null {
  const { status, isAdmin, isTeacher } = useCurrentUser();
  if (status === 'loading') return null;
  return status === 'authenticated' && (isAdmin || isTeacher) ? 'staff' : 'guest';
}
