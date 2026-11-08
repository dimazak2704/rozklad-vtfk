/** «Кренцін» + «Микола» + «Дмитрович» → «Кренцін М.Д.» — так сервер складає коротке ім’я, якщо поле порожнє. */
export function shortNameOf(lastName: string, firstName: string | null, middleName: string | null): string {
  if (!lastName) return '';
  const initials = [firstName, middleName]
    .filter((part): part is string => Boolean(part))
    .map((part) => `${part.charAt(0).toLocaleUpperCase('uk')}.`)
    .join('');
  return initials ? `${lastName} ${initials}` : lastName;
}
