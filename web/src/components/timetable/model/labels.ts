/** Підписи області «Базовий розклад»: види конфліктів, статуси й джерела версій, пояснення метрик. */
import type { TimetableConflictKind } from '@/api/timetable';
import type { Parity, TimetableVersionSource, TimetableVersionStatus } from '@/api/types';

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

/** Пʼять основних показників табло; нові (genfix §3.3) можуть бути null у старих метриках і тут не показуються. */
export type MetricKey = 'groupWindows' | 'teacherWindows' | 'brokenDays' | 'latePairs' | 'maxPairsPerDay';

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
    label: '4-та пара зміни',
    hint: 'Дні груп (окремо чисельник і знаменник) з 4-ю парою зміни: у 1-й зміні — 4 пара, у 2-й — 8 пара; у пʼятницю 2-га зміна має лише 3 пари. Це не правило коледжу: у чинному розкладі 4-та пара зміни є в 57 % днів груп.',
  },
  {
    key: 'maxPairsPerDay',
    label: 'Макс. пар на день',
    hint: 'Найбільше пар в одної групи за день. Більше 4 — перевантажений день (мʼяке попередження).',
  },
];
