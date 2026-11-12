import { useCatalogOptions } from '@/api/catalog';
import { isApiError } from '@/api/errors';
import type { AccessTeacher } from '@/api/access';
import type { ComboboxOption } from '@/components/ui';

export interface TeacherChoice extends ComboboxOption {
  email: string | null;
}

/**
 * Викладачі, яким можна дати доступ: активні й без облікового запису; плюс уже прив'язаний (`current`), щоб
 * його було видно при зміні доступу.
 */
export function useTeacherChoices(current?: AccessTeacher | null) {
  const teachers = useCatalogOptions('teachers', { active: true, linked: false });
  const options: TeacherChoice[] = (teachers.items ?? []).map((t) => ({
    value: t.id,
    label: t.shortName,
    keywords: [t.lastName, t.displayName].filter((v): v is string => Boolean(v)),
    email: t.email ?? null,
  }));
  if (current && !options.some((o) => o.value === current.id)) {
    options.unshift({ value: current.id, label: current.shortName, keywords: [current.displayName], email: null });
  }
  return { options, loading: teachers.isPending, truncated: teachers.truncated };
}

/**
 * Розкладає помилку сервера: помилки полів — у `setFieldError`, решта (409 з кодом, 422, 503) — одним текстом
 * для `AccessFormAlert`. Повертає цей текст або null.
 */
export function serverError(error: unknown, fields: readonly string[], setFieldError: (field: string, message: string) => void): string | null {
  if (!isApiError(error)) return 'Щось пішло не так. Спробуйте ще раз.';
  const fieldErrors = error.fieldErrors();
  let mapped = false;
  for (const field of fields) {
    if (fieldErrors[field]) {
      setFieldError(field, fieldErrors[field]);
      mapped = true;
    }
  }
  return mapped ? null : error.message;
}
