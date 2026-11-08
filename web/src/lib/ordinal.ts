/**
 * Порядкові числівники з дефісом, як пишуть у розкладі й розпорядженнях:
 * «10-й тиждень», «2-га пара», «з 4-ї пари».
 */

/** Закінчення жіночого роду в називному відмінку: 1-ша, 2-га, 3-тя, 4-та, 7-ма, 8-ма. */
function feminineSuffix(n: number): string {
  const lastTwo = Math.abs(n) % 100;
  const last = Math.abs(n) % 10;
  if (lastTwo >= 11 && lastTwo <= 19) return 'та';
  if (last === 1) return 'ша';
  if (last === 2) return 'га';
  if (last === 3) return 'тя';
  if (last === 7 || last === 8) return 'ма';
  return 'та';
}

/** «10-й тиждень» (чоловічий рід, називний відмінок). */
export function weekOrdinal(n: number): string {
  return `${n}-й тиждень`;
}

/** «10-й тиж.» — коротко для щільних рядків. */
export function weekOrdinalShort(n: number): string {
  return `${n}-й тиж.`;
}

/** «2-га» — порядковий номер пари без слова «пара». */
export function pairOrdinal(n: number): string {
  return `${n}-${feminineSuffix(n)}`;
}

/** «2-га пара». */
export function pairOrdinalLabel(n: number): string {
  return `${pairOrdinal(n)} пара`;
}

/** «4-ї» — родовий відмінок: «з 4-ї пари», «до 2-ї пари». */
export function pairOrdinalGenitive(n: number): string {
  return `${n}-ї`;
}
