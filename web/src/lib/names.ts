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
