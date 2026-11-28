/** Підписи для екранів щоденної роботи: пари, періоди відсутності, джерела, суми. */
import type { AbsenceDto, AbsenceSource, ChangeDto, IsoDate, ReasonCode } from '@/api/types';
import { formatDateRange, formatInstantTime, parseIsoDate, todayInKyiv } from '@/lib/date';
import { countLabel, PAIR_FORMS_GEN, REASON_CODE_HINT } from '@/lib/labels';
import { pairOrdinalGenitive } from '@/lib/ordinal';
import { gridTeacherName, type TeacherLabel } from './teacher-label';

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

/**
 * Причина заміни словами для диспетчера: «взаємна заміна». Друковане скорочення («Зв.зам.») — мова аркуша,
 * його видно в самому розпорядженні.
 */
export function reasonWords(code: ReasonCode, text: string | null | undefined): string {
  if (code === 'OTHER') return text?.trim() || REASON_CODE_HINT.OTHER.toLocaleLowerCase('uk');
  return REASON_CODE_HINT[code].toLocaleLowerCase('uk');
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

/** «Інша аудиторія: 406 → 404»; без старої — «Інша аудиторія: 404». */
function roomChangeText(before: string | undefined, after: string | undefined): string {
  if (!after) return 'Інша аудиторія';
  return before && before !== after ? `Інша аудиторія: ${before} → ${after}` : `Інша аудиторія: ${after}`;
}

/** «СТП — Кренцін» без висячих тире, коли якоїсь частини немає. */
function partiesText(side: ChangeDto['new'], name: TeacherLabel): string {
  return [side.discipline?.shortName, side.teacher ? name(side.teacher) : null].filter(Boolean).join(' — ') || 'без змін у складі';
}

/**
 * Що буде з парою після рішення: «Михальчук О. І.», «Пари не буде», «Перенесено з 4-ї пари: …»,
 * «Інша аудиторія: 406 → 404». Ті самі слова, що на сторінці групи й на «Замінах».
 */
export function decisionSummary(change: ChangeDto, name: TeacherLabel = gridTeacherName): string {
  switch (change.kind) {
    case 'CANCEL':
      return 'Пари не буде';
    case 'SWAP':
      // swapFromPair — пара, з якої переставили заняття на звільнене місце (pairFrom)
      return `${change.swapFromPair !== null ? `Перенесено з ${pairOrdinalGenitive(change.swapFromPair)}${NBSP}пари` : 'Перенесено'}: ${partiesText(change.new, name)}`;
    case 'REPLACE_DISCIPLINE':
      return partiesText(change.new, name);
    case 'MERGE_SUBGROUPS':
      return `${change.new.teacher ? name(change.new.teacher) : 'Викладач'}: обидві підгрупи`;
    case 'ADDITIONAL':
      return `Додаткова пара: ${partiesText(change.new, name)}`;
    case 'ROOM_CHANGE':
      return roomChangeText(change.original.room?.name, change.new.room?.name);
    case 'REPLACE_TEACHER':
    case 'BLOCK':
    case 'DISCIPLINE_FINISHED':
      return change.new.teacher ? name(change.new.teacher) : 'Без викладача';
  }
}

const USD = new Intl.NumberFormat('uk-UA', { style: 'currency', currency: 'USD', currencyDisplay: 'narrowSymbol', maximumFractionDigits: 2 });

/** Сума в доларах для витрат ШІ одним форматом на весь застосунок: «0,03 $», «2,00 $» — до центів. */
export function usd(value: number): string {
  return USD.format(value);
}

/**
 * Коли сталася подія (відповідь викладача тощо): сьогодні — «о 01:15», іншого дня — «14.11 о 01:15»,
 * іншого року — «14.11.2025 о 01:15». «Сьогодні» — за годинником застосунку (час стенда в dev).
 */
export function momentText(instant: string, today: IsoDate = todayInKyiv()): string {
  const time = `о ${formatInstantTime(instant)}`;
  const day = todayInKyiv(new Date(instant));
  if (day === today) return time;
  const { year, month, day: dayOfMonth } = parseIsoDate(day);
  const dayMonth = `${String(dayOfMonth).padStart(2, '0')}.${String(month).padStart(2, '0')}`;
  return `${year === parseIsoDate(today).year ? dayMonth : `${dayMonth}.${year}`} ${time}`;
}
