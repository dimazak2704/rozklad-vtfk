import { useCallback } from 'react';
import { useTeacherNameDirectory } from '@/hooks/useTeacherNames';
import { formatTeacherName } from '@/lib/teacher-name';
import type { TeacherLabel } from './teacher-label';

/**
 * Підпис викладача в панелі диспетчера: «Прізвище І. Б.» з імен каталогу. Поки каталог не прийшов —
 * ініціали з короткої форми (а без них — прізвище), без «стрибка» розмітки: довжина майже та сама.
 */
export function useTeacherLabel(): TeacherLabel {
  const names = useTeacherNameDirectory('admin');
  return useCallback<TeacherLabel>((teacher) => formatTeacherName(teacher, 'grid', { names }), [names]);
}
