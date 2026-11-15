import type { ReportRowType } from '@/api/import';
import type { ImportJobStatus, ImportKind, MappingType, ReportSeverity } from '@/api/types';
import type { BadgeTone, SelectOption } from '@/components/ui';
import { countLabel } from '@/lib/labels';

export const IMPORT_KIND_LABEL: Record<ImportKind, string> = {
  TIMETABLE_XLSX: 'Розклад (Excel)',
  DISCIPLINE_PROGRAM: 'Програма дисципліни',
  CURRICULUM: 'Навчальний план',
  ORDER_DOCUMENT: 'Розпорядження',
  ACADEMIC_CALENDAR_XLSX: 'Графік освітнього процесу',
};

/** Що станеться після застосування — підказка у виборі виду. */
export const IMPORT_KIND_HINT: Record<ImportKind, string> = {
  TIMETABLE_XLSX: 'Таблиця коледжу → довідники й чернетка версії розкладу',
  DISCIPLINE_PROGRAM: 'DOCX, DOC чи PDF → години в навантаження вибраних груп',
  CURRICULUM: 'Скан чи фото плану → години на тиждень за дисциплінами',
  ORDER_DOCUMENT: 'DOCX чи фото → рядки розпорядження як історичні зміни',
  ACADEMIC_CALENDAR_XLSX: 'Таблиця графіка (XLSX) → періоди календаря й тижні теорії груп',
};

export const IMPORT_KIND_OPTIONS: ReadonlyArray<SelectOption<ImportKind>> = (
  ['TIMETABLE_XLSX', 'ACADEMIC_CALENDAR_XLSX', 'DISCIPLINE_PROGRAM', 'CURRICULUM', 'ORDER_DOCUMENT'] as const
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

/** «$0.04», «<$0.01» — витрати ШІ без зайвих знаків після коми. */
export function usdShort(value: number): string {
  if (value === 0) return '$0';
  if (value < 0.01) return '<$0.01';
  return `$${value.toFixed(2)}`;
}

/**
 * `summary.unknown` — назви без збігу в каталозі: для розкладу їх створять, для розпорядження вони лише
 * довідкові (воно зберігається текстом), а можливий дублікат ще чекає рішення. Тож не «нові записи».
 */
export const UNKNOWN_FORMS = ['назва не з каталогу', 'назви не з каталогу', 'назв не з каталогу'] as const;

/**
 * Що в звіті одним реченням: «74 рядки розпізнано, 9 назв не з каталогу, 1 чекає рішення, 3 попередження.»
 * Нулі не називаємо.
 */
export function summarySentence(summary: { recognized: number; unknown: number; conflicts: number; warnings: number }): string {
  const parts = [countLabel(summary.recognized, ['рядок', 'рядки', 'рядків']) + ' розпізнано'];
  if (summary.unknown > 0) parts.push(countLabel(summary.unknown, UNKNOWN_FORMS));
  if (summary.conflicts > 0) parts.push(`${summary.conflicts} ${summary.conflicts % 10 === 1 && summary.conflicts % 100 !== 11 ? 'чекає' : 'чекають'} рішення`);
  if (summary.warnings > 0) parts.push(countLabel(summary.warnings, ['попередження', 'попередження', 'попереджень']));
  return `${parts.join(', ')}.`;
}

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
  PERIOD_KIND: 'Вид періоду',
  PERIOD: 'Період',
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
