/**
 * Ініціали з імені: «Кренцін М.Д.» → «КМ», «Микола Кренцін» → «МК», «admin» → «A».
 * Ініціали після прізвища (М.Д.) беруться як друга літера.
 */
export function initials(name: string): string {
  const cleaned = name.replace(/[«»"]/g, '').trim();
  if (!cleaned) return '?';
  const words = cleaned.split(/\s+/).filter(Boolean);
  const first = words[0] ?? '';
  if (words.length === 1) {
    // «Кренцін» або «Кренцін.М.Д.»
    const [surname = '', initial = ''] = first.split('.');
    return (surname.charAt(0) + initial.charAt(0)).toLocaleUpperCase('uk') || '?';
  }
  const second = words[1] ?? '';
  return (first.charAt(0) + second.replace(/[^\p{L}]/gu, '').charAt(0)).toLocaleUpperCase('uk');
}

/**
 * Підпис групи для списків: «4 курс · Комп'ютерна інженерія». Відділення в core може бути null —
 * тоді його просто немає в підписі (ніякого «null»).
 */
export function groupDescription(group: { course: number; department: string | null }, separator = ' · '): string {
  return [`${group.course} курс`, group.department].filter(Boolean).join(separator);
}

/** Назва спеціальності, а якщо вона збігається з літерами — відділення (може не бути зовсім). */
export function groupSpecialtyName(group: { specialty: { letters: string; name: string }; department: string | null }): string | null {
  return group.specialty.name !== group.specialty.letters ? group.specialty.name : group.department;
}
