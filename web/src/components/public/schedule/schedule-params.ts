/**
 * Стан сторінки розкладу в адресі (UI.md §3: посиланням можна поділитися):
 * `?date=2026-11-09&view=week&sub=1` — день, подання і підгрупа.
 */
import { useSearchParams } from 'react-router';
import type { IsoDate } from '@/api/types';
import { addDays, isIsoDate, nearestStudyDay, startOfWeek, todayInKyiv } from '@/lib/date';

export type ScheduleView = 'day' | 'week';

/** Остання явно обрана людиною подача — наступного разу відкриваємо саме її. */
export const VIEW_STORAGE_KEY = 'rozklad.scheduleView';

export function parseView(raw: string | null | undefined): ScheduleView | null {
  return raw === 'day' || raw === 'week' ? raw : null;
}

/** Підгрупа 1..9 або null (уся група). */
export function parseSubgroup(raw: string | null | undefined): number | null {
  if (!raw || !/^[1-9]$/.test(raw)) return null;
  return Number(raw);
}

/** День на екрані: з адреси або сьогодні; вихідні — найближчий понеділок. */
export function resolveScheduleDate(raw: string | null | undefined, today: IsoDate): IsoDate {
  return nearestStudyDay(isIsoDate(raw) ? raw : today);
}

/** Навчальний тиждень дати: понеділок–пʼятниця. */
export function studyWeek(date: IsoDate): { from: IsoDate; to: IsoDate } {
  const from = startOfWeek(date);
  return { from, to: addDays(from, 4) };
}

function storedView(): ScheduleView | null {
  try {
    return parseView(window.localStorage.getItem(VIEW_STORAGE_KEY));
  } catch {
    // сховище вимкнене налаштуваннями браузера
    return null;
  }
}

/** Без вибору людини: на широкому екрані тиждень сіткою, на телефоні — сьогоднішній день. */
export function defaultView(): ScheduleView {
  const stored = storedView();
  if (stored) return stored;
  return typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches ? 'week' : 'day';
}

export interface ScheduleParams {
  /** День на екрані (завжди навчальний: пн–пт). */
  date: IsoDate;
  /** Сьогодні за Києвом. */
  today: IsoDate;
  /** Найближчий навчальний день від сьогодні (для «Сьогодні» у вихідні). */
  homeDate: IsoDate;
  view: ScheduleView;
  /** Підгрупа (лише для групи) або null — уся група. */
  subgroup: number | null;
  week: { from: IsoDate; to: IsoDate };
  setDate: (date: IsoDate) => void;
  setView: (view: ScheduleView) => void;
  setSubgroup: (subgroup: number | null) => void;
}

export function useScheduleParams(): ScheduleParams {
  const [params, setParams] = useSearchParams();
  const today = todayInKyiv();
  const homeDate = nearestStudyDay(today);
  const date = resolveScheduleDate(params.get('date'), today);
  const view = parseView(params.get('view')) ?? defaultView();
  const subgroup = parseSubgroup(params.get('sub'));

  const patch = (changes: Record<string, string | null>) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        for (const [key, value] of Object.entries(changes)) {
          if (value === null) next.delete(key);
          else next.set(key, value);
        }
        return next;
      },
      { replace: true },
    );

  // сьогоднішній день — без параметра: адреса коротша, а завтра посилання відкриє вже «завтрашній» день
  const setDate = (next: IsoDate) => {
    const day = nearestStudyDay(next);
    patch({ date: day === homeDate ? null : day });
  };
  const setView = (next: ScheduleView) => {
    try {
      window.localStorage.setItem(VIEW_STORAGE_KEY, next);
    } catch {
      // без сховища вибір живе лише в адресі
    }
    patch({ view: next });
  };
  const setSubgroup = (next: number | null) => patch({ sub: next === null ? null : String(next) });

  return { date, today, homeDate, view, subgroup, week: studyWeek(date), setDate, setView, setSubgroup };
}
