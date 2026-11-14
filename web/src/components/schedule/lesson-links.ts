import { createContext, useContext } from 'react';
import type { RoomRef } from '@/api/types';

export type ScheduleEntityKind = 'group' | 'teacher' | 'room';

/**
 * Звідки відкрито картку заняття: чий це розклад і з яким поданням і днем. Значення в картці (викладач,
 * аудиторія, група) ведуть на свої розклади з тим самим поданням і днем; сутність сторінки — простий текст.
 */
export interface LessonLinks {
  /** Чий розклад на екрані; null — картка не на сторінці розкладу (головна, адмінка). */
  self: {
    kind: ScheduleEntityKind;
    id: string;
    label: string;
    /**
     * Сторінка аудиторії прибирає свою аудиторію із занять; картка показує її звідси, з типом
     * («ауд. 14 · лабораторія»). Немає — лише `label`.
     */
    room?: RoomRef | null;
  } | null;
  /** Рядок запиту, який переходи зберігають: `?view=week&date=2026-11-16` або ''. */
  search: string;
}

const NONE: LessonLinks = { self: null, search: '' };

export const LessonLinksContext = createContext<LessonLinks>(NONE);

export function useLessonLinks(): LessonLinks {
  return useContext(LessonLinksContext);
}

/** Подання й день, які переходи з картки зберігають. День, що збігається з «сьогоднішнім», не пишемо, як і сторінка. */
export function lessonLinkSearch({ view, date, homeDate }: { view: 'day' | 'week'; date: string; homeDate: string }): string {
  const params = new URLSearchParams({ view });
  if (date !== homeDate) params.set('date', date);
  return `?${params.toString()}`;
}
