/**
 * Імпорт файлів (CONTRACTS §5, сервіс importer): завантаження, розбір у чернетку зі звітом нормалізації,
 * рішення щодо невідомих назв (mappings), застосування в core і облік витрат ШІ.
 *
 * Контракт описує чернетку як `json`. Її форма за видом імпорту — як записи importer
 * (timetable/TimetableDraft, program/ProgramDraft, ai/AiSchemas.order|curriculum); рядок звіту має ще
 * поле `message` з поясненням українською, `score` у збігах — від 0 до 1.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  AiUsageDto,
  ControlForm,
  ImportJobDto,
  ImportJobStatus,
  ImportKind,
  ImportMapping,
  MappingType,
  OrderKind,
  OrderRowType,
  Page,
  Parity,
  ReportRow,
  RoomType,
  Uuid,
  Weekday,
} from './types';

// ───────────────────────── Звіт ─────────────────────────

/** Про що рядок звіту: перші чотири — сутності каталогу, які можна зіставити. */
export type ReportRowType =
  | MappingType
  | 'LESSON'
  | 'CELL'
  | 'BELLS'
  | 'HOURS'
  | 'ORDER'
  | 'CURRICULUM'
  | 'CATALOG'
  | 'FILE';

export interface ImportReportRow extends Omit<ReportRow, 'type'> {
  type: ReportRowType;
}

// ───────────────────────── Чернетки за видом імпорту ─────────────────────────

export interface TimetableDraftGroup {
  code: string;
  /** Як записано у файлі: «ІКІ1», «1ТК 1». */
  raw?: string | null;
  course: number;
  specialtyLetters: string;
  number?: number | null;
  shift: number;
  subgroups: number[];
  homeRoom?: string | null;
  curator?: string | null;
  location?: string | null;
}

export interface TimetableDraftLesson {
  weekday: Weekday;
  pair: number;
  parity: Parity;
  groupCode: string;
  subgroup: number;
  discipline: string;
  teacher?: string | null;
  room?: string | null;
  location?: string | null;
}

export interface TimetableDraft {
  sheet: string;
  title?: string | null;
  semesterLabel?: string | null;
  groups: TimetableDraftGroup[];
  teachers: Array<{ shortName: string; displayName: string; variants: string[]; lessonCount: number; needsReview: boolean }>;
  disciplines: Array<{ shortName: string; name: string; needsReview: boolean; variants: string[]; lessonCount: number }>;
  rooms: Array<{ name: string; type: RoomType; maxParallel: number; variants: string[] }>;
  lessons: TimetableDraftLesson[];
  bellProfiles: Array<{ name: string; slots: Array<{ weekday: Weekday; pair: number; startsAt: string; endsAt: string }> }>;
}

export interface ProgramHours {
  total?: number | null;
  classroom?: number | null;
  selfStudy?: number | null;
  lectures?: number | null;
  practice?: number | null;
  labs?: number | null;
  seminars?: number | null;
  consultations?: number | null;
  exam: boolean;
  credit: boolean;
}

export interface ProgramDraft {
  disciplineName?: string | null;
  disciplineRaw?: string | null;
  cycleCommission?: string | null;
  hours?: ProgramHours | null;
  /** Семестр, надрукований у програмі, — лише довідка: його часто не оновлюють. */
  semester?: number | null;
  hoursPerWeek?: number | null;
  credits?: number | null;
  source: 'TABLE' | 'PDF_TEXT' | 'AI';
  location?: string | null;
}

export interface OrderDraftRow {
  section: 'CHANGES' | 'ROOM_CHANGES';
  groupCode: string;
  pairLabel: string;
  rowType: OrderRowType;
  fromDiscipline?: string | null;
  fromTeacher?: string | null;
  reasonText?: string | null;
  toDiscipline?: string | null;
  toTeacher?: string | null;
  roomText?: string | null;
}

export interface OrderDraft {
  number?: number | null;
  kind: OrderKind;
  parentNumber?: number | null;
  date?: string | null;
  weekdayLabel?: string | null;
  parityLabel?: string | null;
  rows: OrderDraftRow[];
  notes: string[];
  signatoryPosition?: string | null;
  signatoryName?: string | null;
  warnings: string[];
}

export interface CurriculumDraftRow {
  discipline: string;
  hoursPerWeek?: number | null;
  hoursPerSemester?: number | null;
  totalHours?: number | null;
  classroomHours?: number | null;
  lectures?: number | null;
  practice?: number | null;
  labs?: number | null;
  controlForm: ControlForm | 'UNKNOWN';
}

export interface CurriculumDraft {
  semester: number;
  weeks?: number | null;
  rows: CurriculumDraftRow[];
  totalHoursPerWeek?: number | null;
  warnings: string[];
}

/** Крок застосування, якому нічого було робити (наприклад, усі сутності каталогу вже є). */
export interface SkippedStep {
  skipped: true;
}

/** Відповідь core на `POST /api/admin/import/catalog`. */
export interface CoreCatalogImportResult {
  created: number;
  updated: number;
  unchanged: number;
  ids: Record<string, Record<string, Uuid>>;
  warnings: string[];
}

/** Відповідь core на `POST /api/admin/import/timetable`: нова чернетка версії. */
export interface CoreTimetableImportResult {
  versionId: Uuid;
  name: string;
  lessonCount: number;
  hardConflicts: number;
  softWarnings: number;
}

/** Відповідь core на `POST /api/admin/import/load`. */
export interface CoreLoadImportResult {
  created: number;
  updated: number;
  unchanged: number;
  ids: Record<string, Uuid>;
}

/**
 * `result` застосованого завдання — відповіді core за кроками (importer зберігає кожен завершений крок):
 * TIMETABLE_XLSX — `catalog` + `timetable`, програма й навчальний план — `catalog` + `load`, розпорядження — `changes`.
 */
export interface ImportApplyResult {
  catalog?: CoreCatalogImportResult | SkippedStep;
  timetable?: CoreTimetableImportResult;
  load?: CoreLoadImportResult | SkippedStep;
  changes?: Record<string, unknown>;
}

export function isSkipped(step: object | undefined): step is SkippedStep {
  return step !== undefined && 'skipped' in step && (step as SkippedStep).skipped;
}

/**
 * Параметри завантаження (поле `options`, JSON) — рівно ключі, які приймає importer (`JobOptions`);
 * будь-який інший ключ → 400 на `options.<ключ>`.
 */
export interface ImportOptions {
  /** TIMETABLE_XLSX: аркуш (типово «Розклад друк»), до 100 символів. */
  sheet?: string;
  /** TIMETABLE_XLSX: назва чернетки версії, до 100 символів. */
  versionName?: string;
  /** CURRICULUM (обовʼязково): семестр навчального плану 1..8. */
  semesterNumber?: number;
  /** DISCIPLINE_PROGRAM, CURRICULUM (обовʼязково до застосування): коди груп, яким додати навантаження. */
  groupCodes?: string[];
  /** DISCIPLINE_PROGRAM: годин на тиждень, коли програма їх не каже (0 < x ≤ 40). */
  hoursPerWeek?: number;
}

export const IMPORT_OPTION_LIMITS = {
  sheet: 100,
  versionName: 100,
  semesterNumber: { min: 1, max: 8 },
  hoursPerWeek: { min: 0.5, max: 40 },
} as const;

export interface ImportJob extends Omit<ImportJobDto, 'report' | 'options' | 'result'> {
  report: ImportReportRow[];
  options?: ImportOptions;
  result?: ImportApplyResult;
}

/** Чернетка з видом імпорту: розбираємо JSON один раз і перевіряємо форму, а не віримо наосліп. */
export type TypedDraft =
  | { kind: 'TIMETABLE_XLSX'; draft: TimetableDraft }
  | { kind: 'DISCIPLINE_PROGRAM'; draft: ProgramDraft }
  | { kind: 'ORDER_DOCUMENT'; draft: OrderDraft }
  | { kind: 'CURRICULUM'; draft: CurriculumDraft };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Чернетка потрібного виду або null, якщо її ще немає чи вона має іншу форму. */
export function typedDraft(job: Pick<ImportJob, 'kind' | 'draft'>): TypedDraft | null {
  const draft = job.draft;
  if (!isObject(draft)) return null;
  switch (job.kind) {
    case 'TIMETABLE_XLSX':
      return Array.isArray(draft.groups) && Array.isArray(draft.lessons)
        ? { kind: job.kind, draft: draft as unknown as TimetableDraft }
        : null;
    case 'DISCIPLINE_PROGRAM':
      return 'source' in draft ? { kind: job.kind, draft: draft as unknown as ProgramDraft } : null;
    case 'ORDER_DOCUMENT':
      return Array.isArray(draft.rows) && Array.isArray(draft.notes) ? { kind: job.kind, draft: draft as unknown as OrderDraft } : null;
    case 'CURRICULUM':
      return Array.isArray(draft.rows) && typeof draft.semester === 'number'
        ? { kind: job.kind, draft: draft as unknown as CurriculumDraft }
        : null;
  }
}

// ───────────────────────── Стани ─────────────────────────

/** Сервер ще працює над завданням: опитуємо, доки стан не зміниться. */
export const IMPORT_BUSY_STATUSES: ReadonlySet<ImportJobStatus> = new Set(['UPLOADED', 'PARSING', 'APPLYING']);

export const isBusy = (status: ImportJobStatus) => IMPORT_BUSY_STATUSES.has(status);

/** Рядок чекає рішення: сутність без збігу, але з кандидатами (можливий дублікат). */
export function needsDecision(row: ImportReportRow): boolean {
  return isEntityRow(row) && row.severity === 'WARN' && !row.matched && row.suggestions.length > 0;
}

export function isEntityRow(row: ImportReportRow): row is ImportReportRow & { type: MappingType } {
  return row.type === 'TEACHER' || row.type === 'DISCIPLINE' || row.type === 'GROUP' || row.type === 'ROOM';
}

/** Псевдоніми core зберігає лише для викладачів і дисциплін. */
export const supportsAlias = (type: MappingType) => type === 'TEACHER' || type === 'DISCIPLINE';

const POLL_MS = 1200;

function invalidateImport(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: qk.importer.all });
}

// ───────────────────────── Запити ─────────────────────────

export interface ImportJobFilters {
  kind?: ImportKind;
  status?: ImportJobStatus;
}

/** Розмір сторінки списку завдань (importer: 1..100, типово 20). */
export const IMPORT_JOBS_PAGE_SIZE = 20;

/** Завдання імпорту — сторінка `{items,page,size,total}`, нові зверху; поки якесь у роботі — оновлюємо. */
export function useImportJobs(filters: ImportJobFilters = {}, page = 0, size = IMPORT_JOBS_PAGE_SIZE) {
  return useQuery({
    queryKey: qk.importer.jobs({ ...filters, page, size }),
    queryFn: ({ signal }) =>
      api.get<Page<ImportJob>>('/import/jobs', { kind: filters.kind, status: filters.status, page, size }, { signal }),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => (query.state.data?.items.some((job) => isBusy(job.status)) ? POLL_MS : false),
  });
}

/**
 * Одне завдання; опитуємо, поки сервер розбирає чи застосовує файл. Коли застосування завершилось,
 * оновлюємо дані адмінки: імпорт міг змінити каталог, навантаження й розклад.
 */
export function useImportJob(id: Uuid | null | undefined) {
  const queryClient = useQueryClient();
  const key = qk.importer.job(id ?? '');
  return useQuery({
    queryKey: key,
    queryFn: async ({ signal }) => {
      const previous = queryClient.getQueryData<ImportJob>(key);
      const job = await api.get<ImportJob>(`/import/jobs/${id ?? ''}`, undefined, { signal });
      if (previous?.status === 'APPLYING' && job.status !== 'APPLYING') {
        // не чіпаємо сам цей запит: інвалідація скасувала б поточне завантаження й зациклила опитування
        void queryClient.invalidateQueries({ queryKey: qk.admin.all });
        void queryClient.invalidateQueries({ queryKey: qk.importer.jobs() });
        void queryClient.invalidateQueries({ queryKey: qk.importer.aiUsage() });
      }
      return job;
    },
    enabled: Boolean(id),
    refetchInterval: (query) => (query.state.data && isBusy(query.state.data.status) ? POLL_MS : false),
  });
}

export interface UploadImportInput {
  file: File;
  kind: ImportKind;
  semesterId?: Uuid | null;
  options?: ImportOptions | null;
}

/** POST multipart: `file`, `kind`, `semesterId?`, `options?` (JSON) → 202 і завдання в стані UPLOADED. */
export function useUploadImport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, kind, semesterId, options }: UploadImportInput) => {
      const form = new FormData();
      form.append('file', file, file.name);
      form.append('kind', kind);
      if (semesterId) form.append('semesterId', semesterId);
      if (options && Object.keys(options).length > 0) form.append('options', JSON.stringify(options));
      return api.upload<ImportJob>('/import/jobs', form);
    },
    onSuccess: (job) => {
      queryClient.setQueryData(qk.importer.job(job.id), job);
      invalidateImport(queryClient);
    },
  });
}

/** Зберігає рішення щодо невідомих назв; сервер перенормалізує звіт і повертає оновлене завдання. */
export function useSaveMappings(jobId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (mappings: ImportMapping[]) => api.put<ImportJob>(`/import/jobs/${jobId}/mappings`, { mappings }),
    onSuccess: (job) => {
      queryClient.setQueryData(qk.importer.job(job.id), job);
      invalidateImport(queryClient);
    },
  });
}

/** Застосування (202): далі завдання переходить APPLYING → APPLIED, стан приходить опитуванням. */
export function useApplyImport(jobId: Uuid) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<ImportJob | undefined>(`/import/jobs/${jobId}/apply`),
    onSuccess: (job) => {
      if (job) queryClient.setQueryData(qk.importer.job(jobId), job);
      invalidateImport(queryClient);
    },
  });
}

/** Витрати ШІ сьогодні й денний бюджет (AI_DAILY_BUDGET_USD). */
export function useAiUsage() {
  return useQuery({
    queryKey: qk.importer.aiUsage(),
    queryFn: ({ signal }) => api.get<AiUsageDto>('/import/ai/usage', undefined, { signal }),
    refetchInterval: 60_000,
  });
}
