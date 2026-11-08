/**
 * Контрактний тест мок-API проти бекенду: назви полів кожної відповіді мока звіряються з компонентами
 * Java-записів (record) DTO, які справді серіалізує сервер (services/*). Так мок не може тихо приховати
 * розбіжність: нове, перейменоване чи зникле поле DTO валить цей тест, доки мок і типи не оновлено.
 *
 * Читаються сирці, а не OpenAPI: springdoc віддає схему лише з запущеного сервісу, а тест має працювати
 * без бекенду. Записи з `@JsonInclude(NON_NULL)` (importer) допускають відсутність null-полів.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type {
  AbsenceDto,
  BindingCodeDto,
  ChangeDto,
  ChatBindingDto,
  DashboardDto,
  DeliveryDto,
  GenerationJobCreated,
  ImportJobDto,
  Page,
  PublicChangesDto,
  ScheduleDto,
  TimetableVersionDto,
} from '@/api/types';
import { ACTIVE_SEMESTER, groupNamed } from '@/mocks/fixtures';
import { resetMockStores } from '@/mocks/store';

/** Понеділок демо-ранку (як у тестах admin-daily). */
const NOW = new Date('2026-11-09T05:40:00Z');
const DAY = '2026-11-09';

interface RecordShape {
  /** Усі поля JSON (з урахуванням `@JsonProperty`). */
  fields: string[];
  /** Поля, яких може не бути (`@JsonInclude(NON_NULL)` на компоненті чи на всьому записі). */
  optional: Set<string>;
}

type ReadFile = (path: string, encoding: 'utf8') => Promise<string>;
let readFile: ReadFile;
/** Тести запускаються з теки web/, сирці бекенду — поруч у services/. */
let servicesDir = '';

beforeAll(async () => {
  // модулі Node підтягуємо динамічно: у tsconfig застосунку немає типів node (як у import.test.ts)
  const fsModule = 'node:fs/promises';
  ({ readFile } = (await import(/* @vite-ignore */ fsModule)) as { readFile: ReadFile });
  const { process } = globalThis as unknown as { process: { cwd(): string } };
  servicesDir = `${process.cwd().replace(/\\/g, '/')}/../services/`;
});

const sources = new Map<string, string>();

async function source(path: string): Promise<string> {
  const cached = sources.get(path);
  if (cached !== undefined) return cached;
  const text = await readFile(`${servicesDir}${path}`, 'utf8');
  sources.set(path, text);
  return text;
}

/** Ділить рядок за комами верхнього рівня (без ком усередині <>, () і рядків в анотаціях). */
function splitTopLevel(text: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let quoted = false;
  let current = '';
  for (const char of text) {
    if (char === '"') quoted = !quoted;
    if (!quoted && (char === '<' || char === '(')) depth += 1;
    if (!quoted && (char === '>' || char === ')')) depth -= 1;
    if (!quoted && depth === 0 && char === ',') {
      parts.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  if (current.trim()) parts.push(current);
  return parts;
}

/** Компоненти `record Name(...)` у файлі: імʼя поля JSON і чи може воно бути відсутнім. */
async function javaRecord(path: string, name: string): Promise<RecordShape> {
  const text = (await source(path)).replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
  const start = new RegExp(`record\\s+${name}(<[^>]*>)?\\s*\\(`).exec(text);
  if (!start) throw new Error(`У ${path} немає record ${name}`);
  let depth = 1;
  let index = start.index + start[0].length;
  const from = index;
  while (depth > 0 && index < text.length) {
    if (text[index] === '(') depth += 1;
    if (text[index] === ')') depth -= 1;
    index += 1;
  }
  const header = text.slice(Math.max(0, start.index - 200), start.index);
  const recordNonNull = /@JsonInclude\(JsonInclude\.Include\.NON_NULL\)\s*(public\s+)?$/.test(header.trimEnd() + ' ');
  const fields: string[] = [];
  const optional = new Set<string>();
  for (const component of splitTopLevel(text.slice(from, index - 1))) {
    const rename = /@JsonProperty\("([^"]+)"\)/.exec(component)?.[1];
    const cleaned = component.replace(/@\w+(\.\w+)*(\([^)]*\))?/g, ' ').trim();
    const field = rename ?? cleaned.split(/\s+/).at(-1);
    if (!field) continue;
    fields.push(field);
    if (recordNonNull || /@JsonInclude\(JsonInclude\.Include\.NON_NULL\)/.test(component)) optional.add(field);
  }
  return { fields, optional };
}

const CORE = 'core/src/main/java/ua/vtc/rozklad/core/';
const NOTIFY = 'notification/src/main/java/ua/vtc/rozklad/notification/';
const IMPORTER = 'importer/src/main/java/ua/vtc/rozklad/importer/';

/** Ключі відповіді мока = поля DTO (необовʼязкові можуть бути відсутні, зайвих — жодних). */
async function expectShape(value: unknown, path: string, record: string): Promise<void> {
  expect(value, `${record}: відповідь мока порожня`).toBeTypeOf('object');
  expect(value).not.toBeNull();
  const shape = await javaRecord(path, record);
  const keys = Object.keys(value as object).sort();
  const required = shape.fields.filter((f) => !shape.optional.has(f)).sort();
  const extra = keys.filter((k) => !shape.fields.includes(k));
  const missing = required.filter((f) => !keys.includes(f));
  expect({ record, extra, missing }).toEqual({ record, extra: [], missing: [] });
}

function first<T>(list: readonly T[] | undefined, what: string): T {
  const item = list?.[0];
  if (item === undefined) throw new Error(`Мок не повернув жодного запису: ${what}`);
  return item;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  resetMockStores();
  return () => vi.useRealTimers();
});

describe('контракт мок-API ↔ core', () => {
  it('зміни, історія й сторони — ChangeDto', async () => {
    const change = first(await api.get<ChangeDto[]>('/admin/changes', { date: DAY }), 'зміни дати');
    const path = `${CORE}substitution/internal/change/ChangeDto.java`;
    await expectShape(change, path, 'ChangeDto');
    await expectShape(change.original, path, 'Parties');
    await expectShape(first(change.history, 'історія'), path, 'HistoryEntry');
  });

  it('розпорядження, рядок і архів — OrderDto', async () => {
    const path = `${CORE}order/internal/view/OrderDto.java`;
    const draft = await api.get<{ items: unknown[] }>('/admin/orders/draft', { date: DAY });
    await expectShape(draft, path, 'OrderDto');
    await expectShape(first(draft.items, 'рядки чернетки'), path, 'Item');
    const archive = await api.get<Page<unknown>>('/admin/orders', { status: 'PUBLISHED' });
    await expectShape(first(archive.items, 'архів'), path, 'Summary');
  });

  it('панель — DashboardDto і сповіщення', async () => {
    const path = `${CORE}order/internal/dashboard/DashboardDto.java`;
    const dashboard = await api.get<DashboardDto>('/admin/dashboard', { date: DAY });
    await expectShape(dashboard, path, 'DashboardDto');
    await expectShape(first(dashboard.alerts, 'сповіщення'), path, 'Alert');
    await expectShape(dashboard.orderDraft, path, 'OrderDraft');
  });

  it('відсутності, зачеплені пари, LessonView і варіанти', async () => {
    const absence = first(await api.get<AbsenceDto[]>('/admin/absences', { from: DAY, to: DAY }), 'відсутності');
    await expectShape(absence, `${CORE}substitution/internal/absence/AbsenceDto.java`, 'AbsenceDto');
    const affected = await api.get<Array<{ lesson: { id: string }; date?: string }>>(`/admin/absences/${absence.id}/affected`);
    const item = first(affected, 'зачеплені пари');
    // `date` клієнт потребує, але core його ще не надсилає (integration.md §5) — це єдина дозволена різниця
    const { date: _requestedByWeb, ...coreFields } = item;
    await expectShape(coreFields, `${CORE}substitution/internal/absence/AffectedLessonDto.java`, 'AffectedLessonDto');
    await expectShape(item.lesson, `${CORE}timetable/LessonView.java`, 'LessonView');
    const options = await api.get<{ options: unknown[] }>(`/admin/lessons/${item.lesson.id}/options`);
    const path = `${CORE}substitution/internal/options/OptionDto.java`;
    await expectShape(options, path, 'LessonOptions');
    await expectShape(first(options.options, 'варіанти'), path, 'OptionDto');
  });

  it('публічні: meta, дзвінки, довідники, розклад, зміни', async () => {
    const calendar = `${CORE}calendar/internal/CalendarDtos.java`;
    const meta = await api.get<{ semester: unknown; bellProfile: unknown }>('/public/meta');
    await expectShape(meta, calendar, 'PublicMetaDto');
    await expectShape(meta.semester, calendar, 'SemesterRefDto');
    const bells = await api.get<{ slots: unknown[] }>('/public/bells', { date: DAY });
    await expectShape(bells, calendar, 'BellsDto');
    await expectShape(first(bells.slots, 'дзвінки'), calendar, 'SlotDto');

    const catalog = `${CORE}catalog/internal/PublicCatalogDtos.java`;
    await expectShape(first(await api.get<unknown[]>('/public/groups'), 'групи'), catalog, 'PublicGroupDto');
    await expectShape(first(await api.get<unknown[]>('/public/teachers'), 'викладачі'), catalog, 'PublicTeacherDto');
    await expectShape(first(await api.get<unknown[]>('/public/rooms'), 'аудиторії'), catalog, 'PublicRoomDto');

    const view = `${CORE}timetable/ScheduleView.java`;
    const schedule = await api.get<ScheduleDto>(`/public/schedule/group/${groupNamed('4КІ1').id}`, { from: DAY, to: DAY });
    await expectShape(schedule, view, 'ScheduleView');
    await expectShape(schedule.owner, view, 'Owner');
    await expectShape(first(schedule.days, 'дні'), view, 'Day');

    const changesPath = `${CORE}order/internal/publicview/PublicChangesDto.java`;
    const changes = await api.get<PublicChangesDto>('/public/changes', { date: '2026-10-19' });
    await expectShape(changes, changesPath, 'PublicChangesDto');
    await expectShape(changes.order, changesPath, 'PublishedOrder');
    const group = first(changes.groups, 'групи змін');
    await expectShape(group, changesPath, 'GroupChanges');
    const row = first(group.items, 'рядки змін');
    await expectShape(row, changesPath, 'ChangeView');
    await expectShape(row.from, changesPath, 'Side');
  });

  it('налаштування, версії розкладу, задача генерації, профіль користувача', async () => {
    await expectShape(await api.get('/admin/settings'), `${CORE}settings/web/SettingsDto.java`, 'SettingsDto');
    const versions = await api.get<TimetableVersionDto[]>('/admin/timetable/versions', { semesterId: ACTIVE_SEMESTER.id });
    await expectShape(first(versions, 'версії'), `${CORE}timetable/internal/version/TimetableVersionDto.java`, 'TimetableVersionDto');
    const { jobId } = await api.post<GenerationJobCreated>('/admin/generation/jobs', {
      semesterId: ACTIVE_SEMESTER.id,
      scope: {},
      timeLimitSeconds: 10,
    });
    await expectShape(await api.get(`/admin/generation/jobs/${jobId}`), `${CORE}timetable/internal/generation/GenerationJobDto.java`, 'GenerationJobDto');
    await expectShape(await api.get('/me'), `${CORE}identity/UserProfile.java`, 'UserProfile');
  });
});

describe('контракт мок-API ↔ core: каталог, календар, навантаження, облік', () => {
  it('каталог — DTO з version і посиланнями', async () => {
    const catalog = `${CORE}catalog/internal/`;
    const page = <T,>(path: string) => api.get<Page<T>>(path);
    await expectShape(first((await page('/admin/teachers')).items, 'викладачі'), `${catalog}TeacherDtos.java`, 'TeacherDto');
    await expectShape(first((await page('/admin/rooms')).items, 'аудиторії'), `${catalog}RoomDtos.java`, 'RoomDto');
    const group = first((await page<{ subgroups: unknown[] }>('/admin/groups')).items, 'групи');
    await expectShape(group, `${catalog}GroupDtos.java`, 'GroupDto');
    await expectShape(first((await page('/admin/disciplines')).items, 'дисципліни'), `${catalog}DisciplineDtos.java`, 'DisciplineDto');
    await expectShape(first((await page('/admin/departments')).items, 'відділення'), `${catalog}StructureDtos.java`, 'DepartmentDto');
    await expectShape(first((await page('/admin/specialties')).items, 'спеціальності'), `${catalog}StructureDtos.java`, 'SpecialtyDto');
    // у демо-каталозі комісій і корпусів може не бути — тоді перевіряти нічого
    const commission = (await page('/admin/cycle-commissions')).items[0];
    if (commission) await expectShape(commission, `${catalog}StructureDtos.java`, 'CycleCommissionDto');
    const building = (await page('/admin/buildings')).items[0];
    if (building) await expectShape(building, `${catalog}StructureDtos.java`, 'BuildingDto');
  });

  it('календар — семестри, тижні, профілі дзвінків, періоди', async () => {
    const calendar = `${CORE}calendar/internal/CalendarDtos.java`;
    const semester = first(await api.get<Array<{ id: string }>>('/admin/semesters'), 'семестри');
    await expectShape(semester, calendar, 'SemesterDto');
    await expectShape(first(await api.get<unknown[]>(`/admin/semesters/${semester.id}/weeks`), 'тижні'), calendar, 'SemesterWeekDto');
    const profile = first(await api.get<Array<{ slots: unknown[] }>>('/admin/bell-profiles'), 'профілі');
    await expectShape(profile, calendar, 'BellProfileDto');
    await expectShape(first(profile.slots, 'слоти'), calendar, 'BellSlotDto');
    await expectShape(first(await api.get<unknown[]>('/admin/calendar-periods', { semesterId: semester.id }), 'періоди'), calendar, 'CalendarPeriodDto');
    await expectShape(first(await api.get<unknown[]>('/admin/shift-windows'), 'вікна змін'), calendar, 'ShiftWindowDto');
  });

  it('навантаження й облік годин', async () => {
    const item = first(await api.get<Array<{ teachers: unknown[] }>>('/admin/load', { semesterId: ACTIVE_SEMESTER.id }), 'навантаження');
    await expectShape(item, `${CORE}load/internal/LoadDtos.java`, 'LoadItemDto');
    const rows = await api.get<Array<{ suggestion: unknown }>>('/admin/accounting', { semesterId: ACTIVE_SEMESTER.id });
    await expectShape(first(rows, 'облік'), `${CORE}accounting/internal/web/AccountingRowDto.java`, 'AccountingRowDto');
    const suggested = rows.find((row) => row.suggestion);
    if (suggested) await expectShape(suggested.suggestion, `${CORE}accounting/internal/web/AccountingRowDto.java`, 'SuggestionDto');
    const stats = await api.get<unknown[]>('/admin/accounting/teachers', { month: '2026-10' });
    if (stats.length > 0) await expectShape(stats[0], `${CORE}accounting/internal/web/TeacherStatsDto.java`, 'TeacherStatsDto');
  });
});

describe('контракт мок-API ↔ notification', () => {
  it('журнал доставок — сторінка DeliveryDto', async () => {
    const page = await api.get<Page<DeliveryDto>>('/notify/deliveries');
    await expectShape(page, `${NOTIFY}shared/PageResponse.java`, 'PageResponse');
    await expectShape(first(page.items, 'доставки'), `${NOTIFY}delivery/web/DeliveryController.java`, 'DeliveryDto');
  });

  it('привʼязки, код привʼязки й стан бота', async () => {
    const path = `${NOTIFY}binding/web/BindingController.java`;
    await expectShape(first(await api.get<ChatBindingDto[]>('/notify/bindings'), 'привʼязки'), path, 'BindingDto');
    const code = await api.post<BindingCodeDto>('/notify/bindings/codes', { type: 'ADMIN_CHAT' });
    await expectShape(code, path, 'BindingCodeDto');
    await expectShape(await api.get('/notify/bot'), `${NOTIFY}telegram/BotStatus.java`, 'Status');
  });
});

describe('контракт мок-API ↔ importer', () => {
  it('сторінка завдань, завдання з рядком звіту й витрати ШІ', async () => {
    const page = await api.get<Page<ImportJobDto>>('/import/jobs');
    await expectShape(page, `${IMPORTER}web/PageResponse.java`, 'PageResponse');
    const job = first(page.items, 'завдання');
    const dtoPath = `${IMPORTER}job/ImportJobDto.java`;
    await expectShape(job, dtoPath, 'ImportJobDto');
    const detail = await api.get<ImportJobDto>(`/import/jobs/${job.id}`);
    await expectShape(detail, dtoPath, 'ImportJobDto');
    const timetable = first(page.items.filter((j) => j.kind === 'TIMETABLE_XLSX'), 'імпорт розкладу');
    const full = await api.get<ImportJobDto>(`/import/jobs/${timetable.id}`);
    await expectShape(first(full.report, 'звіт'), `${IMPORTER}report/ReportRow.java`, 'ReportRow');
    await expectShape(full.summary, `${IMPORTER}report/ImportSummary.java`, 'ImportSummary');
    const usage = await api.get<{ today: unknown; model: unknown }>('/import/ai/usage');
    const aiPath = `${IMPORTER}ai/AiUsageController.java`;
    await expectShape(usage, aiPath, 'AiUsageDto');
    await expectShape(usage.today, aiPath, 'Today');
    await expectShape(usage.model, aiPath, 'Models');
  });
});
