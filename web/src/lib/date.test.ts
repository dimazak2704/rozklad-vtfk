import { describe, expect, it } from 'vitest';
import {
  addDays,
  diffDays,
  formatDateRange,
  formatDayMonth,
  formatWeekdayDate,
  formatWeekdayDateAfterDo,
  formatWeekdayDateAfterNa,
  isIsoDate,
  isoWeekday,
  kyivClock,
  minutesToTime,
  nearestStudyDay,
  relativeDayLabel,
  shiftStudyDay,
  startOfWeek,
  timeToMinutes,
} from './date';

describe('Київський час', () => {
  it('враховує літній час (UTC+3) у жовтні', () => {
    // 07:00 UTC 19.10.2026 — це 10:00 у Києві
    const clock = kyivClock(new Date('2026-10-19T07:00:00Z'));
    expect(clock.date).toBe('2026-10-19');
    expect(clock.minutes).toBe(10 * 60);
  });

  it('враховує зимовий час (UTC+2) у листопаді', () => {
    const clock = kyivClock(new Date('2026-11-09T06:30:00Z'));
    expect(clock.date).toBe('2026-11-09');
    expect(clock.minutes).toBe(8 * 60 + 30);
  });

  it('дата в Києві вже наступна, хоча в UTC ще вчора', () => {
    expect(kyivClock(new Date('2026-11-08T22:30:00Z')).date).toBe('2026-11-09');
  });
});

describe('календарна арифметика', () => {
  it('додає дні через кінець місяця і перехід часу', () => {
    expect(addDays('2026-10-30', 3)).toBe('2026-11-02');
    expect(addDays('2026-10-25', 1)).toBe('2026-10-26');
    expect(diffDays('2026-10-19', '2026-11-02')).toBe(14);
  });

  it('рахує ISO-день тижня і початок тижня', () => {
    expect(isoWeekday('2026-10-19')).toBe(1);
    expect(isoWeekday('2026-10-25')).toBe(7);
    expect(startOfWeek('2026-10-22')).toBe('2026-10-19');
  });

  it('перескакує вихідні', () => {
    expect(shiftStudyDay('2026-10-23', 1)).toBe('2026-10-26');
    expect(shiftStudyDay('2026-10-26', -1)).toBe('2026-10-23');
    expect(nearestStudyDay('2026-11-07')).toBe('2026-11-09');
  });

  it('перевіряє формат і реальність дати', () => {
    expect(isIsoDate('2026-02-29')).toBe(false);
    expect(isIsoDate('2028-02-29')).toBe(true);
    expect(isIsoDate('19.10.2026')).toBe(false);
  });
});

describe('час пар', () => {
  it('переводить HH:mm у хвилини і назад', () => {
    expect(timeToMinutes('08:30')).toBe(510);
    expect(minutesToTime(510)).toBe('08:30');
    expect(() => timeToMinutes('8.30')).toThrow(RangeError);
  });
});

describe('форматування українською', () => {
  it('використовує родовий відмінок місяця', () => {
    expect(formatDayMonth('2026-10-19')).toBe('19\u00a0жовтня');
    expect(formatWeekdayDate('2026-10-23')).toBe('Пʼятниця, 23\u00a0жовтня');
    // після прийменників — відмінок, а не «на середа», «до понеділок»
    expect(formatWeekdayDateAfterNa('2026-12-23')).toBe('середу, 23\u00a0грудня');
    expect(formatWeekdayDateAfterNa('2026-10-23')).toBe('пʼятницю, 23\u00a0жовтня');
    expect(formatWeekdayDateAfterNa('2026-10-19')).toBe('понеділок, 19\u00a0жовтня');
    expect(formatWeekdayDateAfterNa('2026-10-25')).toBe('неділю, 25\u00a0жовтня');
    expect(formatWeekdayDateAfterDo('2026-10-26')).toBe('понеділка, 26\u00a0жовтня');
    expect(formatWeekdayDateAfterDo('2026-12-23')).toBe('середи, 23\u00a0грудня');
  });

  it('стискає діапазон в одному місяці', () => {
    expect(formatDateRange('2026-10-19', '2026-10-23')).toBe('19–23\u00a0жовтня');
    expect(formatDateRange('2026-10-26', '2026-11-01')).toBe('26\u00a0жовтня – 1\u00a0листопада');
  });

  it('називає сьогодні й завтра', () => {
    expect(relativeDayLabel('2026-10-19', '2026-10-19')).toBe('Сьогодні');
    expect(relativeDayLabel('2026-10-20', '2026-10-19')).toBe('Завтра');
    expect(relativeDayLabel('2026-10-23', '2026-10-19')).toBeNull();
  });
});
