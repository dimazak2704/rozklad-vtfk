/**
 * Облік годин у інтерфейсі: стани, їхня важливість, посилання на «Заміни» для пропозицій і місяці
 * статистики викладачів. Розрахунки плану/факту робить сервер (DOMAIN §9) — тут лише подання.
 */
import type { AccountingRow } from '@/api/accounting';
import type { AccountingStatus } from '@/api/types';
import type { BadgeTone } from '@/components/ui';
import { MONTHS_NOMINATIVE } from '@/lib/labels';

export const STATUS_TONE: Record<AccountingStatus, BadgeTone> = {
  FINISHED: 'accent',
  WILL_OVERRUN: 'change',
  WILL_UNDERRUN: 'cancel',
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
  const params = new URLSearchParams({ group: row.group.id, discipline: row.discipline.id, suggest: suggestion.kind });
  if (suggestion.fromDate) params.set('date', suggestion.fromDate);
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
