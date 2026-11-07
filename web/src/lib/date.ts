/**
 * Дати в часовому поясі коледжу (Europe/Kyiv). Календарні дати зберігаємо як ISO-рядки `YYYY-MM-DD`
 * і рахуємо їх у UTC, щоб перехід на літній час не зсував дні.
 */
import type { IsoDate, IsoTime } from '@/api/types';
import { capitalize, MONTHS_GENITIVE, weekdayName, weekdayShort } from './labels';

export const COLLEGE_TIME_ZONE = 'Europe/Kyiv';

const ISO_DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIME_RE = /^(\d{1,2}):(\d{2})(?::\d{2})?$/;

const partsFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: COLLEGE_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

export interface KyivClock {
  /** Календарна дата в Києві. */
  date: IsoDate;
  /** Хвилини від початку доби (0..1439). */
  minutes: number;
  seconds: number;
}

/** Що зараз показує годинник у Києві. */
export function kyivClock(now: Date = new Date()): KyivClock {
  const parts = partsFormatter.formatToParts(now);
  const get = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((p) => p.type === type)?.value ?? '0');
  const year = get('year');
  const month = get('month');
  const day = get('day');
  const hour = get('hour') % 24;
  return {
    date: toIsoDate(year, month, day),
    minutes: hour * 60 + get('minute'),
    seconds: get('second'),
  };
}

export function todayInKyiv(now: Date = new Date()): IsoDate {
  return kyivClock(now).date;
}

export function toIsoDate(year: number, month: number, day: number): IsoDate {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function isIsoDate(value: unknown): value is IsoDate {
  if (typeof value !== 'string') return false;
  const match = ISO_DATE_RE.exec(value);
  if (!match) return false;
  const [, y, m, d] = match;
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)));
  return date.getUTCFullYear() === Number(y) && date.getUTCMonth() === Number(m) - 1 && date.getUTCDate() === Number(d);
}

export function parseIsoDate(iso: IsoDate): { year: number; month: number; day: number } {
  const match = ISO_DATE_RE.exec(iso);
  if (!match) throw new RangeError(`Некоректна дата: ${iso}`);
  return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
}

function toUtc(iso: IsoDate): Date {
  const { year, month, day } = parseIsoDate(iso);
  return new Date(Date.UTC(year, month - 1, day));
}

function fromUtc(date: Date): IsoDate {
  return toIsoDate(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate());
}

export function addDays(iso: IsoDate, days: number): IsoDate {
  const date = toUtc(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return fromUtc(date);
}

/** Різниця в днях: b − a. */
export function diffDays(a: IsoDate, b: IsoDate): number {
  return Math.round((toUtc(b).getTime() - toUtc(a).getTime()) / 86_400_000);
}

/** ISO-день тижня: 1 = понеділок … 7 = неділя. */
export function isoWeekday(iso: IsoDate): number {
  const day = toUtc(iso).getUTCDay();
  return day === 0 ? 7 : day;
}

export function isWeekend(iso: IsoDate): boolean {
  return isoWeekday(iso) >= 6;
}

/** Понеділок тижня, якому належить дата. */
export function startOfWeek(iso: IsoDate): IsoDate {
  return addDays(iso, 1 - isoWeekday(iso));
}

/** П'ятниця того ж навчального тижня. */
export function endOfStudyWeek(iso: IsoDate): IsoDate {
  return addDays(startOfWeek(iso), 4);
}

/** Наступний/попередній навчальний день (пропускає вихідні). */
export function shiftStudyDay(iso: IsoDate, direction: 1 | -1): IsoDate {
  let next = addDays(iso, direction);
  while (isWeekend(next)) next = addDays(next, direction);
  return next;
}

/** Найближчий навчальний день, починаючи з цієї дати (вихідні → понеділок). */
export function nearestStudyDay(iso: IsoDate): IsoDate {
  return isWeekend(iso) ? shiftStudyDay(iso, 1) : iso;
}

export function compareIsoDates(a: IsoDate, b: IsoDate): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function timeToMinutes(time: IsoTime): number {
  const match = TIME_RE.exec(time);
  if (!match) throw new RangeError(`Некоректний час: ${time}`);
  return Number(match[1]) * 60 + Number(match[2]);
}

export function minutesToTime(minutes: number): IsoTime {
  const normalized = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(normalized / 60)).padStart(2, '0')}:${String(normalized % 60).padStart(2, '0')}`;
}

/** «19 жовтня» */
export function formatDayMonth(iso: IsoDate): string {
  const { month, day } = parseIsoDate(iso);
  return `${day}\u00a0${MONTHS_GENITIVE[month - 1] ?? ''}`;
}

/** «19 жовтня 2026» */
export function formatLongDate(iso: IsoDate): string {
  return `${formatDayMonth(iso)} ${parseIsoDate(iso).year}`;
}

/** «Понеділок, 19 жовтня» */
export function formatWeekdayDate(iso: IsoDate): string {
  return `${capitalize(weekdayName(isoWeekday(iso)))}, ${formatDayMonth(iso)}`;
}

/** «Пн, 19.10» */
export function formatShortWeekdayDate(iso: IsoDate): string {
  const { month, day } = parseIsoDate(iso);
  return `${weekdayShort(isoWeekday(iso))}, ${String(day).padStart(2, '0')}.${String(month).padStart(2, '0')}`;
}

/** «19.10.2026» */
export function formatNumericDate(iso: IsoDate): string {
  const { year, month, day } = parseIsoDate(iso);
  return `${String(day).padStart(2, '0')}.${String(month).padStart(2, '0')}.${year}`;
}

/** «19–23 жовтня», «28 жовтня – 1 листопада» */
export function formatDateRange(from: IsoDate, to: IsoDate): string {
  const a = parseIsoDate(from);
  const b = parseIsoDate(to);
  if (from === to) return formatDayMonth(from);
  if (a.year === b.year && a.month === b.month) return `${a.day}–${b.day}\u00a0${MONTHS_GENITIVE[b.month - 1] ?? ''}`;
  return `${formatDayMonth(from)} – ${formatDayMonth(to)}`;
}

/** «Сьогодні», «Завтра», «Вчора» або null. */
export function relativeDayLabel(iso: IsoDate, today: IsoDate): string | null {
  const delta = diffDays(today, iso);
  if (delta === 0) return 'Сьогодні';
  if (delta === 1) return 'Завтра';
  if (delta === -1) return 'Вчора';
  return null;
}

/** Час події з ISO-instant у київському часі: «08:41». */
export function formatInstantTime(instant: string): string {
  return new Intl.DateTimeFormat('uk-UA', {
    timeZone: COLLEGE_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(instant));
}

/** «19.10.2026, 08:41» */
export function formatInstant(instant: string): string {
  const date = new Date(instant);
  return `${formatNumericDate(todayInKyiv(date))}, ${formatInstantTime(instant)}`;
}

/** Календарна дата для Date-обʼєкта з календаря (локальні поля → ISO без зсуву поясу). */
export function isoFromLocalDate(date: Date): IsoDate {
  return toIsoDate(date.getFullYear(), date.getMonth() + 1, date.getDate());
}

/** Локальний Date (північ) для ISO-дати — для компонентів календаря. */
export function localDateFromIso(iso: IsoDate): Date {
  const { year, month, day } = parseIsoDate(iso);
  return new Date(year, month - 1, day);
}
