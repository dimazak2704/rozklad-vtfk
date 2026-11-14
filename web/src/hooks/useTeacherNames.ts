import { useCallback, useMemo } from 'react';
import { useCatalogOptions } from '@/api/catalog';
import { usePublicTeachers } from '@/api/public';
import type { Uuid } from '@/api/types';
import { formatTeacherName, type TeacherNameSource } from '@/lib/teacher-name';

type TeacherLike = TeacherNameSource & { id: Uuid };

/**
 * «Прізвище Імʼя По батькові» для викладача з будь-якого DTO. Посилання на викладача в розкладі й замінах (TeacherRef)
 * імені не має, тож беремо його з довідника за id: на публічних сторінках — з /api/public/teachers (імʼя там
 * є, лише коли налаштування «показувати повні імена» увімкнене), в адмінці — з каталогу. Поки довідник не
 * прийшов чи імені немає — прізвище з ініціалами («Кренцін М. Д.»), як у сітці.
 */
export function useTeacherFullName(scope: 'public' | 'admin' = 'public'): (teacher: TeacherLike) => string {
  const publicTeachers = usePublicTeachers();
  // каталог адмінки — лише в адмінці: на публічних сторінках цей запит дав би 401
  const catalog = useCatalogOptions('teachers', {}, { enabled: scope === 'admin' });
  const source = scope === 'admin' ? catalog.items : publicTeachers.data;

  const firstNames = useMemo(() => {
    const out = new Map<Uuid, Pick<TeacherNameSource, 'firstName' | 'lastName' | 'middleName' | 'fullName'>>();
    for (const teacher of source ?? []) {
      out.set(teacher.id, {
        firstName: teacher.firstName ?? null,
        lastName: 'lastName' in teacher ? teacher.lastName : null,
        middleName: 'middleName' in teacher ? teacher.middleName : null,
        fullName: 'fullName' in teacher ? teacher.fullName : null,
      });
    }
    return out;
  }, [source]);

  return useCallback(
    (teacher: TeacherLike) => {
      const known = firstNames.get(teacher.id);
      return formatTeacherName(
        {
          ...teacher,
          firstName: teacher.firstName ?? known?.firstName,
          lastName: teacher.lastName ?? known?.lastName,
          middleName: teacher.middleName ?? known?.middleName,
          fullName: teacher.fullName ?? known?.fullName,
        },
        'full',
      );
    },
    [firstNames],
  );
}
