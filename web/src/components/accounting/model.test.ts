import { describe, expect, it } from 'vitest';
import type { AccountingRow } from '@/api/accounting';
import {
  accountingSummary,
  barScale,
  changesLink,
  matchesFilter,
  monthLabel,
  needsAttention,
  shiftMonth,
  statusLabel,
  suggestionAction,
} from './model';

const base: AccountingRow = {
  group: { id: 'g-4ki1', code: '4КІ1', course: 4 },
  discipline: {
    id: 'd-stp',
    shortName: 'СТП',
    name: 'Сучасні технології проєктування',
    color: null,
  },
  plan: 48,
  fact: 38,
  remaining: 32,
  forecast: 70,
  status: 'WILL_OVERRUN',
  forecastFinishDate: '2026-11-23',
  suggestion: {
    kind: 'DISCIPLINE_FINISHED',
    text: 'Вичитано 23 листопада: зняти 11 пар після цієї дати',
    fromDate: '2026-11-24',
    lessons: 11,
    lessonIds: [],
  },
};

describe('облік годин у інтерфейсі', () => {
  it('підписує стан числом розбіжності', () => {
    expect(statusLabel(base)).toBe('Перевищить на 22 год');
    expect(statusLabel({ ...base, status: 'WILL_UNDERRUN', plan: 40, forecast: 34 })).toBe('Не вистачить 6 год');
    expect(statusLabel({ ...base, status: 'FINISHED' })).toBe('Вичитано');
    expect(statusLabel({ ...base, status: 'ON_TRACK' })).toBe('За планом');
  });

  it('«потребують уваги» — розбіжність з планом або пропозиція дії', () => {
    expect(needsAttention(base)).toBe(true);
    expect(needsAttention({ ...base, status: 'FINISHED', suggestion: null })).toBe(false);
    expect(needsAttention({ ...base, status: 'FINISHED' })).toBe(true);
    expect(matchesFilter({ ...base, status: 'ON_TRACK', suggestion: null }, 'attention')).toBe(false);
    expect(matchesFilter(base, 'WILL_OVERRUN')).toBe(true);
    expect(matchesFilter(base, 'all')).toBe(true);
  });

  it('пропозиція веде в «Заміни» з датою першої зайвої пари, групою й дисципліною', () => {
    expect(changesLink(base)).toBe('/admin/changes?group=g-4ki1&discipline=d-stp&suggest=DISCIPLINE_FINISHED&date=2026-11-24');
    expect(
      changesLink({
        ...base,
        suggestion: {
          kind: 'ADDITIONAL',
          text: 'Додати 3 пари',
          fromDate: null,
          lessons: 3,
        },
      }),
    ).toBe('/admin/changes?group=g-4ki1&discipline=d-stp&suggest=ADDITIONAL');
    expect(changesLink({ ...base, suggestion: null })).toBeNull();
  });

  it('гортання місяців і шкала смуги', () => {
    expect(shiftMonth('2026-12', 1)).toBe('2027-01');
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(monthLabel('2026-10')).toBe('жовтень 2026');
    expect(barScale(base)).toBeCloseTo(72.8);
    expect(barScale({ plan: 0, forecast: 0 })).toBeCloseTo(1.04);
  });

  it('підсумок — одне речення замість лічильників', () => {
    expect(accountingSummary([])).toBe('Плану годин у цьому семестрі ще немає.');
    expect(accountingSummary([{ status: 'ON_TRACK' }, { status: 'FINISHED' }])).toBe('Усі дисципліни йдуть за планом.');
    expect(accountingSummary([{ status: 'WILL_UNDERRUN' }, { status: 'WILL_UNDERRUN' }])).toBe('2\u00a0дисципліни не встигнуть вичитати.');
    expect(accountingSummary([{ status: 'WILL_OVERRUN' }, { status: 'WILL_UNDERRUN' }])).toBe('1\u00a0дисципліна перевищить план, 1 не встигне вичитати.');
  });

  it('дія з пропозиції — без повтору стану', () => {
    expect(
      suggestionAction({
        kind: 'ADDITIONAL',
        text: 'Не вистачає 4 год: додати 2 пари',
      }),
    ).toBe('Додати 2 пари');
    expect(suggestionAction(base.suggestion!)).toBe('Зняти 11 пар після цієї дати');
    expect(suggestionAction({ kind: 'ADDITIONAL', text: 'Додати 3 пари' })).toBe('Додати пари');
  });
});
