/** Підписи області «Базовий розклад»: види конфліктів, статуси й джерела версій, пояснення метрик. */
import type { TimetableConflictKind } from '@/api/timetable';
import type { MetricsDto, Parity, TimetableVersionSource, TimetableVersionStatus } from '@/api/types';

export const CONFLICT_KIND_LABEL: Record<TimetableConflictKind, string> = {
  TEACHER: 'Викладач',
  GROUP: 'Група',
  ROOM: 'Аудиторія',
  CAPACITY: 'Місткість',
  ROOM_TYPE: 'Тип аудиторії',
  SHIFT: 'Поза зміною',
  UNAVAILABLE: 'Викладач недоступний',
  WINDOW: 'Вікно',
  BROKEN_DAY: 'Рваний день',
  DAY_OVERLOAD: 'Перевантажений день',
  DISCIPLINE_REPEAT: 'Повтор дисципліни',
  UNWANTED: 'Небажаний час',
};

export const VERSION_STATUS_LABEL: Record<TimetableVersionStatus, string> = {
  DRAFT: 'Чернетка',
  PUBLISHED: 'Опублікована',
  ARCHIVED: 'Архів',
};

export const VERSION_SOURCE_LABEL: Record<TimetableVersionSource, string> = {
  MANUAL: 'вручну',
  IMPORT: 'імпорт з Excel',
  GENERATED: 'генератор',
  COPY: 'копія',
};

/** Коротко про парність для клітинок і підказок. */
export const PARITY_SHORT: Record<Parity, string> = {
  BOTH: 'щотижня',
  NUMERATOR: 'чисельник',
  DENOMINATOR: 'знаменник',
};

export type MetricKey = Exclude<keyof MetricsDto, 'perGroup'>;

/** Метрики версії: назва, пояснення для підказки і чи «менше — краще». */
export const METRICS: ReadonlyArray<{ key: MetricKey; label: string; hint: string }> = [
  {
    key: 'groupWindows',
    label: 'Вікна груп',
    hint: 'Порожні пари між заняттями групи в один день — окремо для чисельника і знаменника. Студенти чекають без пари.',
  },
  {
    key: 'teacherWindows',
    label: 'Вікна викладачів',
    hint: 'Порожні пари між заняттями викладача в один день. Менше вікон — компактніший робочий день.',
  },
  {
    key: 'brokenDays',
    label: 'Рвані дні',
    hint: 'Дні, коли в чисельник і знаменник у групи різна кількість пар або вони починаються з різної пари. Дві половинки різних дисциплін в одному слоті роблять день рівним.',
  },
  {
    key: 'latePairs',
    label: 'Пізні пари',
    hint: 'Заняття після 16:00 — 7-ма і 8-ма пари (друга зміна, пн–чт). Генератор ставить їх лише тоді, коли інакше не виходить.',
  },
  {
    key: 'maxPairsPerDay',
    label: 'Макс. пар на день',
    hint: 'Найбільше пар в одної групи за день. Більше 4 — перевантажений день (мʼяке попередження).',
  },
];
