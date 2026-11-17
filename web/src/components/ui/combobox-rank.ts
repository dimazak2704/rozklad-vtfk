import { matchScore } from '@/lib/search';

/** Найслабший збіг, який ще не є одрукою: підрядок у назві (див. `matchScore`). */
const STRONG_MATCH = 60;

interface RankableOption {
  label: string;
  keywords?: string[];
}

/**
 * Варіанти комбобокса для запиту: найкращий збіг першим, рівні — у вихідному порядку.
 * Одруки (нечіткий збіг) показуємо лише тоді, коли точнішого збігу немає:
 * «4КІ1» не повинен тягнути за собою 1КІ1, 2КІ1 і 3КІ1, що відрізняються однією цифрою.
 * Запит із цифрою (код групи, номер аудиторії) одрук не допускає зовсім: «4КІ3» — це не 4КІ1.
 */
export function rankOptions<T extends RankableOption>(options: readonly T[], query: string): readonly T[] {
  if (!query.trim()) return options;
  const scored = options
    .map((option, index) => ({ option, index, score: matchScore(query, option.label, ...(option.keywords ?? [])) }))
    .filter((entry) => entry.score > 0);
  const hasStrong = /\d/.test(query) || scored.some((entry) => entry.score >= STRONG_MATCH);
  return scored
    .filter((entry) => !hasStrong || entry.score >= STRONG_MATCH)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map((entry) => entry.option);
}
