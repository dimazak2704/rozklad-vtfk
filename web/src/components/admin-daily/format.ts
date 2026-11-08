/** Підписи для екранів щоденної роботи: пари, періоди відсутності, джерела, суми. */
import type { AbsenceDto, AbsenceSource, ChangeDto } from '@/api/types';
import { formatDateRange } from '@/lib/date';
import { countLabel, PAIR_FORMS_GEN } from '@/lib/labels';

const PAIRS_FORMS = ['пара', 'пари', 'пар'] as const;

/** null — увесь день; [7, 8] → «7–8 пари»; [1, 3, 4] → «1, 3–4 пари». */
export function pairsText(pairs: readonly number[] | null | undefined): string {
  if (!pairs || pairs.length === 0) return 'увесь день';
  const sorted = [...pairs].sort((a, b) => a - b);
  const parts: string[] = [];
  let start = sorted[0] as number;
  let prev = start;
  for (const pair of [...sorted.slice(1), Number.NaN]) {
    if (pair === prev + 1) {
      prev = pair;
      continue;
    }
    parts.push(start === prev ? String(start) : `${start}–${prev}`);
    start = pair;
    prev = pair;
  }
  const single = sorted.length === 1;
  return `${parts.join(', ')} ${single ? 'пара' : 'пари'}`;
}

export function absencePeriod(absence: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>): string {
  return `${formatDateRange(absence.dateFrom, absence.dateTo)}, ${pairsText(absence.pairs)}`;
}

export const ABSENCE_SOURCE_TEXT: Record<AbsenceSource, string> = {
  BOT: 'через Telegram-бота',
  WEB: 'з кабінету викладача',
  ADMIN: 'додано диспетчером',
};

/** Нерозривний пробіл між числом і словом («4 пари» не розірветься на два рядки). */
export const NBSP = String.fromCharCode(0xa0);

export function pairsCount(count: number): string {
  return countLabel(count, PAIRS_FORMS);
}

/** Після прийменника: «для 1 пари», «для 5 пар». */
export function pairsCountGenitive(count: number): string {
  return countLabel(count, PAIR_FORMS_GEN);
}

/** «СТП — Кренцін» без висячих тире, коли якоїсь частини немає. */
function partiesText(side: ChangeDto['new']): string {
  return [side.discipline?.shortName, side.teacher?.shortName].filter(Boolean).join(' — ') || 'без змін у складі';
}

/** Що буде з парою після рішення: «→ Михальчук», «Пари не буде», «4 пара → на 2». */
export function decisionSummary(change: ChangeDto): string {
  switch (change.kind) {
    case 'CANCEL':
      return 'Пари не буде';
    case 'SWAP':
      // swapFromPair — пара, з якої переставили заняття на звільнене місце (pairFrom)
      return `${change.swapFromPair !== null ? `Переставлено ${change.swapFromPair} пару` : 'Перестановка'}: ${partiesText(change.new)}`;
    case 'REPLACE_DISCIPLINE':
      return partiesText(change.new);
    case 'MERGE_SUBGROUPS':
      return `${change.new.teacher?.shortName ?? "Викладач"}: обидві підгрупи`;
    case 'ADDITIONAL':
      return `Додаткова пара: ${partiesText(change.new)}`;
    case 'ROOM_CHANGE':
      return change.new.room ? `Аудиторія ${change.new.room.name}` : 'Заміна аудиторії';
    case 'REPLACE_TEACHER':
    case 'BLOCK':
    case 'DISCIPLINE_FINISHED':
      return change.new.teacher?.shortName ?? 'Без викладача';
  }
}

/** Сума в доларах для витрат ШІ: дрібні суми — до тисячних. */
export function usd(value: number): string {
  const digits = value > 0 && value < 0.1 ? 4 : 2;
  return `${new Intl.NumberFormat('uk-UA', { minimumFractionDigits: 2, maximumFractionDigits: digits }).format(value)}${NBSP}$`;
}
