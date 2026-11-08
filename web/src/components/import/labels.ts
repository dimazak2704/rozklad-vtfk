import type { ReportRowType } from '@/api/import';
import type { ImportJobStatus, ImportKind, MappingType, ReportSeverity } from '@/api/types';
import type { BadgeTone, SelectOption } from '@/components/ui';

export const IMPORT_KIND_LABEL: Record<ImportKind, string> = {
  TIMETABLE_XLSX: 'Розклад (Excel)',
  DISCIPLINE_PROGRAM: 'Програма дисципліни',
  CURRICULUM: 'Навчальний план',
  ORDER_DOCUMENT: 'Розпорядження',
};

/** Що станеться після застосування — підказка у виборі виду. */
export const IMPORT_KIND_HINT: Record<ImportKind, string> = {
  TIMETABLE_XLSX: 'Таблиця коледжу → довідники й чернетка версії розкладу',
  DISCIPLINE_PROGRAM: 'DOCX, DOC чи PDF → години в навантаження вибраних груп',
  CURRICULUM: 'Скан чи фото плану → години на тиждень за дисциплінами',
  ORDER_DOCUMENT: 'DOCX чи фото → рядки розпорядження як історичні зміни',
};

export const IMPORT_KIND_OPTIONS: ReadonlyArray<SelectOption<ImportKind>> = (
  ['TIMETABLE_XLSX', 'DISCIPLINE_PROGRAM', 'CURRICULUM', 'ORDER_DOCUMENT'] as const
).map((kind) => ({ value: kind, label: IMPORT_KIND_LABEL[kind], description: IMPORT_KIND_HINT[kind] }));

export const IMPORT_STATUS_LABEL: Record<ImportJobStatus, string> = {
  UPLOADED: 'Завантажено',
  PARSING: 'Розбираємо',
  NEEDS_REVIEW: 'Потрібні рішення',
  READY: 'Готово до застосування',
  APPLYING: 'Застосовуємо',
  APPLIED: 'Застосовано',
  FAILED: 'Помилка',
};

export const IMPORT_STATUS_TONE: Record<ImportJobStatus, BadgeTone> = {
  UPLOADED: 'neutral',
  PARSING: 'neutral',
  NEEDS_REVIEW: 'warning',
  READY: 'accent',
  APPLYING: 'accent',
  APPLIED: 'success',
  FAILED: 'danger',
};

export const SEVERITY_LABEL: Record<ReportSeverity, string> = {
  OK: 'Збіг',
  INFO: 'Довідка',
  WARN: 'Увага',
  ERROR: 'Помилка',
};

export const SEVERITY_TONE: Record<ReportSeverity, BadgeTone> = {
  OK: 'neutral',
  INFO: 'room',
  WARN: 'change',
  ERROR: 'danger',
};

export const SEVERITY_ORDER: Record<ReportSeverity, number> = { ERROR: 0, WARN: 1, INFO: 2, OK: 3 };

export const ROW_TYPE_LABEL: Record<ReportRowType, string> = {
  TEACHER: 'Викладач',
  DISCIPLINE: 'Дисципліна',
  GROUP: 'Група',
  ROOM: 'Аудиторія',
  LESSON: 'Заняття',
  CELL: 'Клітинка',
  BELLS: 'Дзвінки',
  HOURS: 'Години',
  ORDER: 'Розпорядження',
  CURRICULUM: 'План',
  CATALOG: 'Каталог',
  FILE: 'Файл',
  OPTIONS: 'Параметри',
};

/** Знахідний відмінок для кнопок: «Створити викладача». */
export const ENTITY_ACCUSATIVE: Record<MappingType, string> = {
  TEACHER: 'викладача',
  DISCIPLINE: 'дисципліну',
  GROUP: 'групу',
  ROOM: 'аудиторію',
};

/** Відсоток збігу для підпису: score приходить від 0 до 1 (як ScoredRef у importer). */
export const scorePercent = (score: number): number => Math.round(score * 100);
