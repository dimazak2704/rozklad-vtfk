/**
 * Імпорт графіка освітнього процесу в календар (CONTRACTS, «Види періодів» §3, `POST /api/admin/import/calendar-periods`)
 * і чернетка importer для `ACADEMIC_CALENDAR_XLSX` (§5). Мок спрощує core: ділить період за семестрами навчального
 * року, оновлює лише рядки `IMPORT` за ключем, ручні не чіпає й лише називає, а зайві рядки `IMPORT` прибирає.
 * Живих замін мок не знає (`conflicts` порожні), пари не перераховує (`lessons` — нулі).
 */
import { http, HttpResponse } from 'msw';
import type { CalendarPeriod } from '@/api/calendar';
import type {
  AcademicCalendarDraft,
  CalendarImportGroupSemester,
  CalendarImportPeriod,
  CoreCalendarImportResult,
  ImportOptions,
  ImportReportRow,
} from '@/api/import';
import type { FieldErrorDto, IsoDate, Uuid } from '@/api/types';
import { renderNoteTemplate } from '@/components/calendar/notes';
import { compareIsoDates, formatNumericDate } from '@/lib/date';
import { kindRef, legacyKindOf, type PeriodKindFixture } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, readJson } from '../kit';
import { createMockStore } from '../store';
import { periodKinds, periods, semesters } from './calendar';
import { groupRefOf, groups, roomRefOf, rooms } from './catalog';

export interface CalendarImportRequest {
  academicYear: string;
  periods: CalendarImportPeriod[];
  groupSemesters: CalendarImportGroupSemester[];
}

const ROMAN = ['', 'I', 'II'];
const lower = (value: string) => value.toLocaleLowerCase('uk');

/** Вид за кодом чи псевдонімом, без урахування регістру. */
export function kindByCode(code: string): PeriodKindFixture | undefined {
  return periodKinds.all().find((k) => [k.code, ...k.aliases].some((token) => lower(token) === lower(code)));
}

/** Тижні теорії й іспити груп у семестрах (`group_semester`), ключ — `semesterId|groupId`. */
const groupSemesters = createMockStore<Map<string, CalendarImportGroupSemester>>(() => new Map());

function validate(body: CalendarImportRequest): void {
  const errors: FieldErrorDto[] = [];
  if (!/^\d{4}\/\d{4}$/.test(body.academicYear ?? '')) errors.push({ field: 'academicYear', message: 'Навчальний рік має вигляд 2026/2027.' });
  const keys = new Set<string>();
  (body.periods ?? []).forEach((item, i) => {
    if (!item.sourceKey || item.sourceKey.length > 200) errors.push({ field: `periods[${i}].sourceKey`, message: 'Ключ — від 1 до 200 символів.' });
    else if (keys.has(item.sourceKey)) errors.push({ field: `periods[${i}].sourceKey`, message: 'Ключ повторюється.' });
    keys.add(item.sourceKey);
    const kind = kindByCode(item.kindCode);
    if (!kind) errors.push({ field: `periods[${i}].kindCode`, message: `Невідомий вид «${item.kindCode}».` });
    else if (!kind.active) errors.push({ field: `periods[${i}].kindCode`, message: `Вид «${item.kindCode}» вимкнено.` });
    (item.groupCodes ?? []).forEach((code, j) => {
      if (!groups.all().some((g) => g.code === code)) errors.push({ field: `periods[${i}].groupCodes[${j}]`, message: `Групу «${code}» не знайдено.` });
    });
  });
  if (errors.length > 0) throw badRequest('Перевірте дані графіка.', errors);
}

interface Produced {
  storedKey: string;
  item: CalendarImportPeriod;
  semesterId: Uuid;
  startsOn: IsoDate;
  endsOn: IsoDate;
}

/** Поля періоду, які імпорт порівнює з наявним рядком. */
function fieldsOf(part: Produced): Omit<CalendarPeriod, 'id' | 'version'> {
  const kind = kindByCode(part.item.kindCode) as PeriodKindFixture;
  const chosen = (part.item.groupCodes ?? []).map((code) => groups.all().find((g) => g.code === code)).filter((g) => g !== undefined);
  const room = part.item.roomName ? rooms.all().find((r) => r.name === part.item.roomName) : undefined;
  return {
    semesterId: part.semesterId,
    kind: legacyKindOf(kind),
    kindId: kind.id,
    periodKind: kindRef(kind),
    title: part.item.title,
    startsOn: part.startsOn,
    endsOn: part.endsOn,
    studyMode: part.item.studyMode,
    orderNote: part.item.orderNote,
    scope: part.item.scope,
    courses: part.item.scope === 'COURSES' ? (part.item.courses ?? []) : null,
    groupIds: part.item.scope === 'GROUPS' ? chosen.map((g) => g.id) : null,
    groups: part.item.scope === 'GROUPS' ? chosen.map(groupRefOf) : [],
    roomId: room?.id ?? null,
    room: room ? roomRefOf(room) : null,
    supervisorIds: [],
    supervisors: [],
    source: 'IMPORT',
    sourceKey: part.storedKey,
  };
}

const COMPARED = ['kindId', 'title', 'startsOn', 'endsOn', 'scope', 'courses', 'groupIds', 'roomId', 'studyMode', 'orderNote'] as const;

function differs(stored: CalendarPeriod, next: ReturnType<typeof fieldsOf>): string[] {
  return COMPARED.filter((field) => JSON.stringify(stored[field] ?? null) !== JSON.stringify(next[field] ?? null)).map((f) =>
    f === 'kindId' ? 'kindCode' : f === 'groupIds' ? 'groupCodes' : f === 'roomId' ? 'roomName' : f,
  );
}

const codesOf = (period: Pick<CalendarPeriod, 'groups'>) => period.groups.map((g) => g.code);

/** Масове оновлення календаря з графіка: як core, у пробному режимі нічого не зберігає. */
export function importCalendarPeriods(body: CalendarImportRequest, dryRun: boolean): CoreCalendarImportResult {
  validate(body);
  const year = semesters.all().filter((s) => s.academicYear === body.academicYear);
  const produced: Produced[] = [];
  const outOfSemester: CoreCalendarImportResult['outOfSemester'] = [];
  for (const item of body.periods) {
    const parts = year
      .map((s) => ({
        semester: s,
        startsOn: compareIsoDates(item.startsOn, s.startsOn) > 0 ? item.startsOn : s.startsOn,
        endsOn: compareIsoDates(item.endsOn, s.endsOn) < 0 ? item.endsOn : s.endsOn,
      }))
      .filter((p) => compareIsoDates(p.startsOn, p.endsOn) <= 0);
    const keys = parts.map((p) => (parts.length === 1 ? item.sourceKey : `${item.sourceKey}|S${p.semester.number}`));
    const kept = parts.map((p, i) => ({ storedKey: keys[i] as string, semesterId: p.semester.id, startsOn: p.startsOn, endsOn: p.endsOn }));
    parts.forEach((p, i) => produced.push({ storedKey: keys[i] as string, item, semesterId: p.semester.id, startsOn: p.startsOn, endsOn: p.endsOn }));
    let covered = 0;
    for (const p of parts) covered += Math.round((Date.parse(p.endsOn) - Date.parse(p.startsOn)) / 86_400_000) + 1;
    const total = Math.round((Date.parse(item.endsOn) - Date.parse(item.startsOn)) / 86_400_000) + 1;
    if (covered < total) {
      outOfSemester.push({ sourceKey: item.sourceKey, kindCode: item.kindCode, startsOn: item.startsOn, endsOn: item.endsOn, groupCodes: item.groupCodes ?? [], kept });
    }
  }
  const result: CoreCalendarImportResult = {
    dryRun,
    academicYear: body.academicYear,
    canApply: true,
    created: 0,
    updated: 0,
    unchanged: 0,
    deleted: 0,
    skippedManual: 0,
    periods: [],
    manual: [],
    manualOverlaps: [],
    outOfSemester,
    groupSemesters: { created: 0, updated: 0, unchanged: 0, skipped: [] },
    conflicts: [],
    lessons: { added: 0, removed: 0, updated: 0 },
  };
  const writes: Array<() => void> = [];
  const producedKeys = new Set(produced.map((p) => p.storedKey));
  for (const part of produced) {
    const stored = periods.all().find((p) => p.sourceKey === part.storedKey);
    const next = fieldsOf(part);
    const entry = { sourceKey: part.item.sourceKey, storedKey: part.storedKey, semesterId: part.semesterId, kindCode: part.item.kindCode, startsOn: part.startsOn, endsOn: part.endsOn, groupCodes: part.item.groupCodes ?? [] };
    if (!stored) {
      result.created += 1;
      const id = mockId('calendar-period', `import:${part.storedKey}`);
      result.periods.push({ ...entry, action: 'CREATE', periodId: dryRun ? null : id });
      writes.push(() => periods.insert({ id, ...next, version: 0 }));
    } else if (stored.source === 'MANUAL') {
      result.skippedManual += 1;
      result.periods.push({ ...entry, action: 'SKIP_MANUAL', periodId: stored.id });
      result.manual.push({ storedKey: part.storedKey, periodId: stored.id, title: stored.title, differs: differs(stored, next) });
    } else if (differs(stored, next).length > 0) {
      result.updated += 1;
      result.periods.push({ ...entry, action: 'UPDATE', periodId: stored.id });
      writes.push(() => periods.update(stored.id, (current) => ({ ...current, ...next, version: current.version + 1 })));
    } else {
      result.unchanged += 1;
      result.periods.push({ ...entry, action: 'UNCHANGED', periodId: stored.id });
    }
  }
  const yearIds = new Set(year.map((s) => s.id));
  for (const stale of periods.all().filter((p) => p.source === 'IMPORT' && yearIds.has(p.semesterId) && p.sourceKey && !producedKeys.has(p.sourceKey))) {
    result.deleted += 1;
    result.periods.push({
      sourceKey: stale.sourceKey as string,
      storedKey: stale.sourceKey as string,
      action: 'DELETE',
      periodId: stale.id,
      semesterId: stale.semesterId,
      kindCode: stale.periodKind?.code ?? '',
      startsOn: stale.startsOn,
      endsOn: stale.endsOn,
      groupCodes: codesOf(stale),
    });
    writes.push(() => periods.remove(stale.id));
  }
  for (const row of body.groupSemesters) {
    const semester = year.find((s) => s.number === row.semesterNumber);
    const group = groups.all().find((g) => g.code === row.groupCode);
    if (!semester || !group) {
      result.groupSemesters.skipped.push({
        groupCode: row.groupCode,
        semesterNumber: row.semesterNumber,
        reason: semester ? `Групу «${row.groupCode}» не знайдено.` : `Семестру ${ROMAN[row.semesterNumber] ?? row.semesterNumber} ${body.academicYear} немає в системі.`,
      });
      continue;
    }
    const key = `${semester.id}|${group.id}`;
    const before = groupSemesters.get().get(key);
    if (!before) result.groupSemesters.created += 1;
    else if (before.theoryWeeks !== row.theoryWeeks || before.exams !== row.exams) result.groupSemesters.updated += 1;
    else result.groupSemesters.unchanged += 1;
    writes.push(() => groupSemesters.get().set(key, row));
  }
  if (!dryRun) for (const write of writes) write();
  return result;
}

// ───────────────────────── Чернетка importer ─────────────────────────

const YEAR = '2026/2027';
const SHEET = '2026-2027';
/** Типові назви й аудиторії за кодом (рішення власника), як `titles` і `rooms` у параметрах importer. */
const DEFAULT_TITLES: Readonly<Record<string, string>> = { НС: 'Основи національного спротиву' };
const DEFAULT_ROOMS: Readonly<Record<string, string>> = { НС: '401' };

interface Run {
  code: string;
  groupCodes: string[] | null;
  startsOn: IsoDate;
  endsOn: IsoDate;
  cells: string;
  hash: string;
}

/**
 * ДЕМО-прогони клітинок графіка 2026/27: НС 4КІ1 — справжній (16.11–27.11, ауд. 401), решта — зразки форми:
 * НС ще однієї групи, зимові канікули через межу семестру, НС 4ЕА1 у січні (до II семестру системи лише частково,
 * як у §1 проєкту) й невідомий код «Ф».
 */
const RUNS: readonly Run[] = [
  { code: 'НС', groupCodes: ['4КІ1'], startsOn: '2026-11-16', endsOn: '2026-11-27', cells: 'T61:U61', hash: '2a3c2b4f' },
  { code: 'НС', groupCodes: ['4КІ2'], startsOn: '2026-11-30', endsOn: '2026-12-11', cells: 'V62:W62', hash: '2a3c2b4f' },
  { code: 'К', groupCodes: null, startsOn: '2026-12-28', endsOn: '2027-01-08', cells: 'Z5:AA60', hash: '7d1e0c3a' },
  { code: 'НС', groupCodes: ['4ЕА1'], startsOn: '2027-01-11', endsOn: '2027-01-22', cells: 'AB55:AC55', hash: '2a3c2b4f' },
  { code: 'Ф', groupCodes: ['3ЕА2'], startsOn: '2026-12-14', endsOn: '2026-12-18', cells: 'X44', hash: '5b8f9e21' },
];

/** Примітка за шаблоном виду (та сама мова шаблонів, що в «Скласти» форми періоду); без шаблону — без примітки. */
function orderNoteFor(kind: PeriodKindFixture, run: Run, title: string, roomName: string | null): string | null {
  if (!kind.noteTemplate) return null;
  return renderNoteTemplate(kind.noteTemplate, {
    template: kind.noteTemplate,
    kindName: kind.name,
    title,
    startsOn: run.startsOn,
    endsOn: run.endsOn,
    scope: run.groupCodes ? 'GROUPS' : 'ALL',
    courses: [],
    groupCodes: run.groupCodes ?? [],
    studyMode: null,
    roomName,
  }).text;
}

const shortDate = (iso: IsoDate) => formatNumericDate(iso).slice(0, 5);

function reportRow(severity: ImportReportRow['severity'], type: ImportReportRow['type'], raw: string, location: string, message: string): ImportReportRow {
  return { severity, type, raw, location, message, suggestions: [] };
}

/**
 * Чернетка і звіт графіка за параметрами: невідомий код — помилка, поки вид не додано чи код не пропущено;
 * перевірка core (dryRun) — на кожне перебудування звіту, як у importer.
 */
export function academicCalendarDraft(options: ImportOptions | null): { report: ImportReportRow[]; draft: AcademicCalendarDraft } {
  const skip = new Set(options?.skipCodes ?? []);
  const titles = { ...DEFAULT_TITLES, ...options?.titles };
  const roomsByCode = { ...DEFAULT_ROOMS, ...options?.rooms };
  const report: ImportReportRow[] = [];
  const codes = new Map<string, AcademicCalendarDraft['codes'][number]>();
  const items: CalendarImportPeriod[] = [];
  for (const run of RUNS) {
    const kind = kindByCode(run.code);
    const cells = run.cells.includes(':') ? 10 : 1;
    const known = codes.get(run.code);
    codes.set(run.code, {
      code: run.code,
      kindCode: kind?.code ?? null,
      kindName: kind?.name ?? null,
      effect: kind?.effect ?? null,
      cells: (known?.cells ?? 0) + cells,
    });
    if (skip.has(run.code)) continue;
    if (!kind) {
      report.push(reportRow('ERROR', 'PERIOD_KIND', run.code, run.cells, `Невідомий код «${run.code}» у груп ${(run.groupCodes ?? []).join(', ')}: додайте вид у «Видах періодів» або пропустіть код.`));
      continue;
    }
    if (!kind.active) {
      report.push(reportRow('ERROR', 'PERIOD_KIND', run.code, run.cells, `Вид «${run.code}» вимкнено: увімкніть його або пропустіть код.`));
      continue;
    }
    const title = titles[kind.code] ?? kind.name;
    const roomName = kind.effect === 'SUSPEND' ? (roomsByCode[kind.code] ?? null) : null;
    items.push({
      sourceKey: `${YEAR}|${kind.code}|${run.startsOn}|${run.endsOn}|${run.hash}`,
      kindCode: kind.code,
      title,
      startsOn: run.startsOn,
      endsOn: run.endsOn,
      scope: run.groupCodes ? 'GROUPS' : 'ALL',
      groupCodes: run.groupCodes,
      courses: null,
      roomName,
      studyMode: null,
      orderNote: orderNoteFor(kind, run, title, roomName),
      cells: run.cells,
    });
  }
  const groupSemesterRows: CalendarImportGroupSemester[] = [
    { groupCode: '4КІ1', semesterNumber: 1, theoryWeeks: 20, exams: 4 },
    { groupCode: '4КІ1', semesterNumber: 2, theoryWeeks: null, exams: null },
  ];
  const request = { academicYear: YEAR, periods: items.map(({ cells: _cells, ...item }) => item), groupSemesters: groupSemesterRows };
  const check = importCalendarPeriods(request, true);
  for (const out of check.outOfSemester) {
    const who = out.groupCodes.length > 0 ? out.groupCodes.join(', ') : 'усі групи';
    const kept = out.kept.map((p) => `${shortDate(p.startsOn)}–${shortDate(p.endsOn)}`);
    report.push(
      reportRow(
        'WARN',
        'PERIOD',
        out.sourceKey,
        out.kindCode,
        `${out.kindCode} ${who} ${shortDate(out.startsOn)}–${shortDate(out.endsOn)} поза семестрами системи: ${kept.length > 0 ? `імпортується лише ${kept.join(', ')}` : 'не імпортується'}.`,
      ),
    );
  }
  for (const manual of check.manual.filter((m) => m.differs.length > 0)) {
    report.push(reportRow('WARN', 'PERIOD', manual.storedKey, manual.title, `Період змінено вручну, імпорт його не чіпає (різниця: ${manual.differs.join(', ')}).`));
  }
  report.push(
    reportRow(
      'INFO',
      'PERIOD',
      YEAR,
      'календар',
      `Буде створено ${check.created}, оновлено ${check.updated}, видалено ${check.deleted}, без змін ${check.unchanged}.`,
    ),
  );
  const draft: AcademicCalendarDraft = {
    academicYear: YEAR,
    sheet: SHEET,
    groups: groups
      .all()
      .filter((g) => g.active)
      .map((g, index) => ({ raw: g.code.replace(/(\d)$/, '-$1'), code: g.code, row: 5 + index, theoryWeeks: g.code === '4КІ1' ? [20, null] : [null, null], exams: g.code === '4КІ1' ? [4, null] : [null, null] })),
    codes: [...codes.values()],
    legend: [
      { code: 'К', color: '#FFFFCC', title: 'Канікули' },
      { code: 'НП', color: '#FF6600', title: 'Навчальна радіомонтажна практика' },
    ],
    periods: items,
    groupSemesters: groupSemesterRows,
    check,
  };
  return { report, draft };
}

export const handlers = [
  http.post(apiPath('/admin/import/calendar-periods'), async ({ request }) => {
    const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
    return HttpResponse.json(importCalendarPeriods(await readJson<CalendarImportRequest>(request), dryRun));
  }),
];
