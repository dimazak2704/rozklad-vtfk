/**
 * Спільне для форм каталогу й інших сторінок даних: помилки сервера → поля форми, перетворення
 * порожніх рядків і чисел для тіл запитів.
 */
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { isApiError, withConflicts } from '@/api/errors';

/**
 * Розкладає помилку сервера: повідомлення відомих полів — під ці поля (перше отримує фокус), решта
 * (409 «уже існує», «дані змінилися», поля, яких у формі немає) — текстом над формою.
 * Повертає текст для форми або null, якщо все показано біля полів.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: ReadonlyArray<Path<T>>,
  aliases: Readonly<Record<string, Path<T>>> = {},
): string | null {
  if (!isApiError(error)) return error instanceof Error && error.message ? error.message : 'Не вдалося зберегти. Спробуйте ще раз.';
  const rest: string[] = [];
  let focus = true;
  for (const fieldError of error.errors) {
    const field = (aliases[fieldError.field] ?? fieldError.field) as Path<T>;
    if (fields.includes(field)) {
      setError(field, { type: 'server', message: fieldError.message }, { shouldFocus: focus });
      focus = false;
    } else {
      rest.push(fieldError.message);
    }
  }
  if (error.errors.length > 0 && rest.length === 0) return null;
  if (rest.length > 0) return rest.join(' ');
  return error.requestId && error.status >= 500 ? `${error.message} Код запиту: ${error.requestId}` : withConflicts(error);
}

/** «  301 » → «301», «» → null. */
export function blankToNull(value: string | null | undefined): string | null {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? null : trimmed;
}

/** Чи помилка — конфлікт через використання запису (видалення використаного). */
export function isConflict(error: unknown): boolean {
  return isApiError(error) && error.status === 409;
}
