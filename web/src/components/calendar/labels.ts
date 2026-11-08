import type { CalendarPeriodKind, PeriodScope, StudyMode } from '@/api/types';
import type { BadgeTone, SelectOption } from '@/components/ui';

export const PERIOD_KIND_LABEL: Record<CalendarPeriodKind, string> = {
  HOLIDAY: 'Канікули',
  NO_CLASSES: 'Без занять',
  PRACTICE: 'Практика',
  STUDY_MODE: 'Форма навчання',
  EXAM_SESSION: 'Сесія',
};

/** Що період робить із заняттями — підказка у формі й у таблиці. */
export const PERIOD_KIND_EFFECT: Record<CalendarPeriodKind, string> = {
  HOLIDAY: 'Пар немає ні в кого з указаних груп.',
  NO_CLASSES: 'Окремі дні без пар: свята, день здоровʼя.',
  PRACTICE: 'Пари групи знімаються на час практики.',
  STUDY_MODE: 'Пари лишаються, але позначаються як онлайн чи змішані.',
  EXAM_SESSION: 'Пари лишаються; період видно в календарі й розпорядженнях.',
};

export const PERIOD_KIND_TONE: Record<CalendarPeriodKind, BadgeTone> = {
  HOLIDAY: 'cancel',
  NO_CLASSES: 'cancel',
  PRACTICE: 'change',
  STUDY_MODE: 'room',
  EXAM_SESSION: 'accent',
};

export const PERIOD_KIND_OPTIONS: ReadonlyArray<SelectOption<CalendarPeriodKind>> = (
  ['HOLIDAY', 'NO_CLASSES', 'PRACTICE', 'STUDY_MODE', 'EXAM_SESSION'] as const
).map((kind) => ({ value: kind, label: PERIOD_KIND_LABEL[kind], description: PERIOD_KIND_EFFECT[kind] }));

export const STUDY_MODE_LABEL: Record<StudyMode, string> = {
  OFFLINE: 'Очно',
  ONLINE: 'Дистанційно',
  MIXED: 'Змішано',
};

export const SCOPE_LABEL: Record<PeriodScope, string> = {
  ALL: 'Усі групи',
  COURSES: 'Курси',
  GROUPS: 'Окремі групи',
};
