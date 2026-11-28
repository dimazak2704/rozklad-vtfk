import type { AbsenceReasonCategory, IsoDate, IsoInstant, Nullable, Uuid } from './common';

// ───────────────────────── 5. Importer ─────────────────────────

export type ImportKind = 'TIMETABLE_XLSX' | 'DISCIPLINE_PROGRAM' | 'CURRICULUM' | 'ORDER_DOCUMENT' | 'ACADEMIC_CALENDAR_XLSX';
export type ImportJobStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'NEEDS_REVIEW'
  | 'READY'
  | 'APPLYING'
  | 'APPLIED'
  | 'FAILED';
export type ReportSeverity = 'OK' | 'INFO' | 'WARN' | 'ERROR';
export type MappingType = 'TEACHER' | 'DISCIPLINE' | 'GROUP' | 'ROOM';

export interface ScoredRef {
  id: Uuid;
  label: string;
  score: number;
}

/** Рядок звіту імпорту. importer не пише null-поля (`@JsonInclude(NON_NULL)`): їх просто немає. */
export interface ReportRow {
  severity: ReportSeverity;
  type: string;
  raw: string;
  matched?: ScoredRef;
  suggestions: ScoredRef[];
  location: string;
  /** Пояснення українською (адитивне поле importer). */
  message?: string;
}

/** Формат файлу за вмістом (магічні байти), а не за назвою. */
export type ImportFileType = 'XLSX' | 'DOCX' | 'DOC' | 'PDF' | 'JPEG' | 'PNG' | 'WEBP' | 'GIF';

/**
 * ImportJobDto importer: контракт + адитивні `fileType`, `fileSize`, `updatedAt`, `createdBy`, `semesterId`,
 * `options`, `mappings`, `result`, `canApply`. null-поля не пишуться (`NON_NULL`). У списку `report` порожній,
 * а `draft`, `options`, `mappings`, `result` відсутні.
 */
export interface ImportJobDto {
  id: Uuid;
  kind: ImportKind;
  fileName: string;
  fileType: ImportFileType;
  fileSize: number;
  status: ImportJobStatus;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  createdBy?: string;
  semesterId?: Uuid;
  summary: { recognized: number; unknown: number; conflicts: number; warnings: number };
  report: ReportRow[];
  draft?: unknown;
  options?: unknown;
  mappings?: ImportMapping[];
  ai?: { model: string; inputTokens: number; outputTokens: number; costUsd: number };
  result?: unknown;
  error?: string;
  /**
   * Чи можна застосувати зараз: READY, FAILED з чернеткою або NEEDS_REVIEW після відмови core (`error`) —
   * без блокувальних рядків.
   */
  canApply: boolean;
  /** Версія завдання: її повертають, зберігаючи виправлені рядки розпорядження (PUT …/draft) чи параметри (PUT …/options). */
  version?: number;
}

export interface ImportMapping {
  type: MappingType;
  raw: string;
  targetId?: Nullable<Uuid>;
  createNew?: Nullable<Record<string, unknown>>;
  addAlias: boolean;
}

/** PUT /api/import/jobs/{id}/mappings */
export interface ImportMappingsRequest {
  mappings: ImportMapping[];
}

/** POST /api/import/ai/parse-absence */
export interface ParseAbsenceRequest {
  text: string;
  teacherId?: Nullable<Uuid>;
  today: IsoDate;
}

export interface ParseAbsenceResponse {
  dateFrom: IsoDate;
  dateTo: IsoDate;
  pairs?: Nullable<number[]>;
  reasonCategory: AbsenceReasonCategory;
  confidence: number;
  clarification?: Nullable<string>;
}

/** GET /api/import/ai/usage (адитивно: `remainingUsd`, `enabled`). */
export interface AiUsageDto {
  today: { calls: number; inputTokens: number; outputTokens: number; costUsd: number };
  dailyBudgetUsd: number;
  /** Скільки ще можна витратити сьогодні (не менше 0). */
  remainingUsd: number;
  model: { text: string; vision: string };
  /** Чи налаштовано ШІ (є ключ API). */
  enabled: boolean;
}
