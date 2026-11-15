/**
 * Облік годин у інтерфейсі: стани, їхня важливість, посилання на «Заміни» для пропозицій і місяці
 * статистики викладачів. Розрахунки плану/факту робить сервер (DOMAIN §9) — тут лише подання.
 */
import type { AccountingRow } from '@/api/accounting';
import type { AccountingStatus } from '@/api/types';
import type { BadgeTone } from '@/components/ui';
import { MONTHS_NOMINATIVE, plural } from '@/lib/labels';

/**
 * «Не вистачить» посеред семестру — прогноз, а не помилка: нейтральне чорнило. Найсильніший тон —
 * у перевищення, бо зайві години треба оформити розпорядженням.
 */
export const STATUS_TONE: Record<AccountingStatus, BadgeTone> = {
  FINISHED: 'accent',
  WILL_OVERRUN: 'change',
  WILL_UNDERRUN: 'neutral',
  ON_TRACK: 'neutral',
};

/** Від найважливішого: що перевищить і що не встигне — першими. */
export const STATUS_ORDER: Record<AccountingStatus, number> = {
  WILL_OVERRUN: 0,
  WILL_UNDERRUN: 1,
  FINISHED: 2,
  ON_TRACK: 3,
};

/** Підпис стану з числом: «Перевищить на 22 год», «Не вистачить 6 год». */
export function statusLabel(row: Pick<AccountingRow, 'status' | 'plan' | 'forecast'>): string {
  switch (row.status) {
    case 'FINISHED':
      return 'Вичитано';
    case 'WILL_OVERRUN':
      return `Перевищить на ${row.forecast - row.plan} год`;
    case 'WILL_UNDERRUN':
      return `Не вистачить ${row.plan - row.forecast} год`;
    case 'ON_TRACK':
      return 'За планом';
  }
}

export type StatusFilter = 'attention' | 'all' | AccountingStatus;

/** Колір слова стану в рядку (крапка + слово, без «таблички»). */
export const STATUS_TEXT: Record<AccountingStatus, string> = {
  FINISHED: 'text-add',
  WILL_OVERRUN: 'text-change',
  WILL_UNDERRUN: 'text-muted',
  ON_TRACK: 'text-secondary',
};

/**
 * Дія з пропозиції сервера одним коротким реченням: «Не вистачає 4 год: додати 2 пари» → «Додати 2 пари».
 * Стан уже сказано словом поруч, тому повторювати «не вистачає» не треба.
 */
export function suggestionAction(suggestion: { kind: 'DISCIPLINE_FINISHED' | 'ADDITIONAL'; text: string }): string {
  const tail = suggestion.text.split(':').slice(1).join(':').trim();
  if (tail) return tail.charAt(0).toLocaleUpperCase('uk') + tail.slice(1);
  return suggestion.kind === 'DISCIPLINE_FINISHED' ? 'Оформити «Вичит.»' : 'Додати пари';
}

const DISCIPLINE_FORMS = ['дисципліна', 'дисципліни', 'дисциплін'] as const;

/**
 * Один підсумок замість ряду лічильників — тим самим числом, що й фільтр «Потребують уваги»:
 * «95 дисциплін потребують уваги, 1 з них перевищить план.» / «Усі дисципліни йдуть за планом.»
 * Перевищення — окремо, бо це зайві години, які треба оформити розпорядженням.
 */
export function accountingSummary(rows: ReadonlyArray<Pick<AccountingRow, 'status'>>, attention: number): string {
  if (rows.length === 0) return 'Плану годин у цьому семестрі ще немає.';
  if (attention === 0) return 'Усі дисципліни йдуть за планом.';
  const over = rows.filter((row) => row.status === 'WILL_OVERRUN').length;
  const head = `${attention}\u00a0${plural(attention, DISCIPLINE_FORMS)} ${attention === 1 ? 'потребує' : 'потребують'} уваги`;
  if (over === 0) return `${head}.`;
  return `${head}, ${over} з них ${over === 1 ? 'перевищить' : 'перевищать'} план.`;
}

/** Потребує дії диспетчера: є пропозиція або прогноз розходиться з планом. */
export function needsAttention(row: AccountingRow): boolean {
  return row.status === 'WILL_OVERRUN' || row.status === 'WILL_UNDERRUN' || Boolean(row.suggestion);
}

export function matchesFilter(row: AccountingRow, filter: StatusFilter): boolean {
  if (filter === 'all') return true;
  if (filter === 'attention') return needsAttention(row);
  return row.status === filter;
}

/**
 * Посилання на «Заміни» для пропозиції: дата (робоча дата сторінки — параметр `date`), група й
 * дисципліна, яку стосується дія. «Вичитано» — з першої зайвої пари; «додати пари» — без дати.
 */
export function changesLink(row: AccountingRow): string | null {
  const suggestion = row.suggestion;
  if (!suggestion) return null;
  const params = new URLSearchParams({
    group: row.group.id,
    discipline: row.discipline.id,
    suggest: suggestion.kind,
  });
  if (suggestion.fromDate) params.set('date', suggestion.fromDate);
  // скільки пар бракує — форма «Додаткова пара» нагадує про це диспетчеру
  if (suggestion.kind === 'ADDITIONAL' && suggestion.lessons > 0) params.set('lessons', String(suggestion.lessons));
  return `/admin/changes?${params.toString()}`;
}

/** `2026-10` ± n місяців. */
export function shiftMonth(month: string, delta: number): string {
  const [year = 0, mon = 1] = month.split('-').map(Number);
  const index = year * 12 + (mon - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

/** «жовтень 2026». */
export function monthLabel(month: string): string {
  const [year, mon = 1] = month.split('-').map(Number);
  return `${MONTHS_NOMINATIVE[mon - 1] ?? ''} ${year}`;
}

/** Межі шкали смуги годин: трохи більше за найбільше з плану й прогнозу. */
export function barScale(row: Pick<AccountingRow, 'plan' | 'forecast'>): number {
  return Math.max(row.plan, row.forecast, 1) * 1.04;
}
