import type { PublicGroupDto } from '@/api/types';
import { groupSpecialtyName } from '@/lib/names';

/** Повна назва групи для підказки: «Комп'ютерна інженерія · 4 курс». */
export function groupHintText(group: Pick<PublicGroupDto, 'course' | 'specialty' | 'department'>): string {
  return [groupSpecialtyName(group), `${group.course} курс`].filter(Boolean).join(' · ');
}

