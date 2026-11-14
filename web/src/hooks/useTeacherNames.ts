import { useCallback, useMemo } from 'react';
import { useCatalogOptions } from '@/api/catalog';
import { usePublicTeachers } from '@/api/public';
import type { Uuid } from '@/api/types';
import {
  formatTeacherName,
  namePartsFromFull,
  type TeacherNameDirectory,
  type TeacherNameParts,
  type TeacherNameSource,
} from '@/lib/teacher-name';

type TeacherLike = TeacherNameSource & { id: Uuid };

/**
 * Імена викладачів за id. Посилання на викладача в розкладі й замінах (TeacherRef) несе лише коротку форму,
 * тож імʼя й по батькові беремо з довідника: на публічних сторінках — з /api/public/teachers (там вони є, лише
 * коли налаштування «показувати повні імена» увімкнене), в адмінці — з каталогу. Поки довідник не прийшов —
 * порожньо, і формати падають на коротку форму.
 */
export function useTeacherNameDirectory(scope: 'public' | 'admin' = 'public'): TeacherNameDirectory {
  const publicTeachers = usePublicTeachers();
  // каталог адмінки — лише в адмінці: на публічних сторінках цей запит дав би 401
  const catalog = useCatalogOptions('teachers', {}, { enabled: scope === 'admin' });
  const catalogItems = catalog.items;
  const publicItems = scope === 'admin' ? undefined : publicTeachers.data;

  return useMemo(() => {
    const out = new Map<Uuid, TeacherNameParts>();
    for (const teacher of catalogItems ?? []) {
      out.set(teacher.id, {
        lastName: teacher.lastName,
        firstName: teacher.firstName,
        middleName: teacher.middleName,
        fullName: null,
      });
    }
    for (const teacher of publicItems ?? []) out.set(teacher.id, namePartsFromFull(teacher.fullName, teacher.firstName));
    return out;
  }, [catalogItems, publicItems]);
}

/**
 * «Прізвище Імʼя По батькові» для викладача з будь-якого DTO, з довідника імен (`useTeacherNameDirectory`).
 * Поки довідник не прийшов чи імені немає — прізвище з ініціалами («Кренцін М. Д.»), як у сітці.
 */
export function useTeacherFullName(scope: 'public' | 'admin' = 'public'): (teacher: TeacherLike) => string {
  const names = useTeacherNameDirectory(scope);
  return useCallback((teacher: TeacherLike) => formatTeacherName(teacher, 'full', { names }), [names]);
}
