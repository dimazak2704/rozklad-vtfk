/**
 * Що за файл кинули: вид імпорту за розширенням і назвою. Сервер усе одно перевіряє вміст (magic bytes);
 * тут лише зручний вибір за замовчуванням і рання відмова для явно чужих файлів.
 */
import type { ImportKind } from '@/api/types';

export const ACCEPTED_EXTENSIONS = ['xlsx', 'docx', 'doc', 'pdf', 'jpg', 'jpeg', 'png'] as const;
export const ACCEPT_ATTRIBUTE = ACCEPTED_EXTENSIONS.map((ext) => `.${ext}`).join(',');
/** Межа importer (CONTRACTS §5): 20 МБ. */
export const MAX_IMPORT_BYTES = 20 * 1024 * 1024;

/** Які формати приймає кожен вид (так само перевіряє сервер). */
export const KIND_EXTENSIONS: Readonly<Record<ImportKind, readonly string[]>> = {
  TIMETABLE_XLSX: ['xlsx'],
  DISCIPLINE_PROGRAM: ['docx', 'doc', 'pdf'],
  CURRICULUM: ['pdf', 'jpg', 'jpeg', 'png', 'xlsx'],
  ORDER_DOCUMENT: ['docx', 'jpg', 'jpeg', 'png', 'pdf'],
};

export function extensionOf(fileName: string): string {
  return /\.([a-z0-9]+)$/i.exec(fileName)?.[1]?.toLowerCase() ?? '';
}

/** Чи підходить файл для виду імпорту. */
export function kindAccepts(kind: ImportKind, fileName: string): boolean {
  return KIND_EXTENSIONS[kind].includes(extensionOf(fileName));
}

const has = (name: string, ...words: string[]) => words.some((word) => name.includes(word));

export interface Detection {
  kind: ImportKind;
  /** Чому так вирішили — показуємо людині, щоб вона могла не погодитись. */
  reason: string;
}

/**
 * Вид за назвою й розширенням: таблиця Excel — розклад (або план, якщо так названо), документ —
 * програма (або розпорядження), фото — розпорядження (або план). null — файл не підходить.
 */
export function detectImportKind(fileName: string): Detection | null {
  const ext = extensionOf(fileName);
  const name = fileName.toLocaleLowerCase('uk');
  const curriculum = has(name, 'план', 'plan', 'curriculum');
  const order = has(name, 'розпоряд', 'наказ', 'order', 'замін', 'zamin');
  const program = has(name, 'програм', 'program', 'робоча');
  switch (ext) {
    case 'xlsx':
      return curriculum
        ? { kind: 'CURRICULUM', reason: 'Таблиця з назвою «план»' }
        : { kind: 'TIMETABLE_XLSX', reason: 'Таблиця Excel — схоже на розклад занять' };
    case 'docx':
      if (order) return { kind: 'ORDER_DOCUMENT', reason: 'Документ з назвою «розпорядження»' };
      return { kind: 'DISCIPLINE_PROGRAM', reason: program ? 'Документ з назвою «програма»' : 'Документ Word — найчастіше це програма дисципліни' };
    case 'doc':
      return { kind: 'DISCIPLINE_PROGRAM', reason: 'Документ Word 97–2003 — програма дисципліни' };
    case 'pdf':
      if (curriculum) return { kind: 'CURRICULUM', reason: 'PDF з назвою «план»' };
      if (order) return { kind: 'ORDER_DOCUMENT', reason: 'PDF з назвою «розпорядження»' };
      return { kind: 'DISCIPLINE_PROGRAM', reason: 'PDF — найчастіше програма дисципліни' };
    case 'jpg':
    case 'jpeg':
    case 'png':
      return curriculum
        ? { kind: 'CURRICULUM', reason: 'Фото з назвою «план»' }
        : { kind: 'ORDER_DOCUMENT', reason: 'Фото чи скриншот — найчастіше розпорядження' };
    default:
      return null;
  }
}

/** Помилка вибору файлу або null: невідомий формат, порожній чи завеликий файл. */
export function fileProblem(file: Pick<File, 'name' | 'size'>): string | null {
  if (!(ACCEPTED_EXTENSIONS as readonly string[]).includes(extensionOf(file.name))) {
    return `Файл «${file.name}» не підходить: потрібен XLSX, DOCX, DOC, PDF, JPG або PNG.`;
  }
  if (file.size === 0) return `Файл «${file.name}» порожній.`;
  if (file.size > MAX_IMPORT_BYTES) return `Файл «${file.name}» більший за 20 МБ.`;
  return null;
}

/** «1,4 МБ», «820 КБ». */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} МБ`;
  return `${Math.max(1, Math.round(bytes / 1024))} КБ`;
}

/** Чи вид імпорту потребує моделі зору (і витрачає бюджет ШІ). */
export function usesVision(kind: ImportKind, fileName: string): boolean {
  return (kind === 'ORDER_DOCUMENT' || kind === 'CURRICULUM') && ['jpg', 'jpeg', 'png', 'pdf'].includes(extensionOf(fileName));
}
