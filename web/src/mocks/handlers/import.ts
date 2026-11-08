/**
 * Імпорт (CONTRACTS §5): завдання з чернеткою і звітом нормалізації, рішення щодо назв, застосування,
 * витрати ШІ. Стан завдання просувається опитуванням (кожен GET — крок), тож e2e і тести детерміновані:
 * UPLOADED → PARSING → NEEDS_REVIEW | READY; APPLYING → APPLIED.
 *
 * Дані звітів і чернеток — справжні: таблиця розкладу з fixtures (з одруківками DOMAIN §10: «Граболінська»,
 * «Герасменко», «ІКІ1»…), рядки фото розпорядження №35 (seed/orders), таблиця структури програми
 * «Економіка та управління ІТ-проєктами» (seed/programs), рядки навчального плану КІ (DOMAIN §4).
 * Синтетичні лише час створення, токени й вартість викликів ШІ (позначено ДЕМО).
 */
import { http, HttpResponse } from 'msw';
import type {
  CurriculumDraft,
  ImportApplyResult,
  ImportJob,
  ImportOptions,
  ImportReportRow,
  OrderDraft,
  ProgramDraft,
  TimetableDraft,
} from '@/api/import';
import type {
  AiUsageDto,
  ImportFileType,
  ImportJobStatus,
  ImportKind,
  ImportMapping,
  MappingType,
  ReportSeverity,
  RoomType,
  ScoredRef,
  Uuid,
} from '@/api/types';
import { kyivClock } from '@/lib/date';
import { ukCollator } from '@/lib/sort';
import { DISCIPLINES, GROUPS, ROOMS, TEACHERS, TEMPLATE_LESSONS, BELL_PROFILES } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, businessRule, conflict, paginate, problem, readJson } from '../kit';
import { createMockCollection } from '../store';
import { activeSemester, semesters } from './calendar';
import {
  disciplineRefOf,
  disciplines,
  ensureAlias,
  groupRefOf,
  groups,
  rooms,
  teachers,
} from './catalog';
import { loadItems } from './load';
import { extensionOf, KIND_EXTENSIONS, MAX_IMPORT_BYTES, usesVision } from '@/components/import/detect';
import { loadPattern } from '@/components/load/pattern';

const KINDS: readonly ImportKind[] = ['TIMETABLE_XLSX', 'DISCIPLINE_PROGRAM', 'CURRICULUM', 'ORDER_DOCUMENT'];
const DAILY_BUDGET_USD = 2;
const TEXT_MODEL = 'claude-haiku-5-5';
const VISION_MODEL = 'claude-sonnet-5-5';

// ───────────────────────── Стан ─────────────────────────

/**
 * Завдання в памʼяті мока: поля можуть бути null. У відповідь іде `toDto` — як ImportJobDto importer
 * (`@JsonInclude(NON_NULL)`: null-полів немає), а список — без звіту, чернетки, параметрів і результату.
 */
interface StoredJob {
  id: Uuid;
  kind: ImportKind;
  fileName: string;
  fileType: ImportFileType;
  fileSize: number;
  status: ImportJobStatus;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  summary: ImportJob['summary'];
  report: ImportReportRow[];
  draft: unknown;
  ai: NonNullable<ImportJob['ai']> | null;
  error: string | null;
  semesterId: Uuid | null;
  options: ImportOptions | null;
  mappings: ImportMapping[];
  result: ImportApplyResult | null;
  /** Скільки опитувань лишилось до наступного кроку стану. */
  pendingPolls: number;
  /** Коли рахувались витрати ШІ (для денного ліміту). */
  aiAt: string | null;
}

/** Формат за розширенням (importer визначає за вмістом; у моку файли справжні лише в e2e). */
function fileTypeOf(fileName: string): ImportFileType {
  const ext = extensionOf(fileName);
  const map: Readonly<Record<string, ImportFileType>> = { xlsx: 'XLSX', docx: 'DOCX', doc: 'DOC', pdf: 'PDF', jpg: 'JPEG', jpeg: 'JPEG', png: 'PNG', webp: 'WEBP', gif: 'GIF' };
  return map[ext] ?? 'PDF';
}

const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();

// ───────────────────────── Звіт: допоміжне ─────────────────────────

/** Рядок звіту як ReportRow importer: `matched` і `message` немає, коли вони null (NON_NULL). */
const row = (
  severity: ReportSeverity,
  type: ImportReportRow['type'],
  raw: string,
  location: string,
  message: string | null,
  matched: ScoredRef | null = null,
  suggestions: ScoredRef[] = [],
): ImportReportRow => ({
  severity,
  type,
  raw,
  ...(matched ? { matched } : {}),
  suggestions,
  location,
  ...(message ? { message } : {}),
});

const scored = (id: Uuid, label: string, score: number): ScoredRef => ({ id, label, score });

function summaryOf(report: readonly ImportReportRow[]): ImportJob['summary'] {
  let recognized = 0;
  let unknown = 0;
  let conflicts = 0;
  let warnings = 0;
  for (const r of report) {
    const entity = r.type === 'TEACHER' || r.type === 'DISCIPLINE' || r.type === 'GROUP' || r.type === 'ROOM';
    if (entity) {
      if (r.matched) recognized += 1;
      else unknown += 1;
    }
    const decision = entity && r.severity === 'WARN' && !r.matched && r.suggestions.length > 0;
    if (r.severity === 'ERROR' || decision) conflicts += 1;
    else if (r.severity === 'WARN') warnings += 1;
  }
  return { recognized, unknown, conflicts, warnings };
}

/** Назва стовпця Excel за номером з нуля: 0 → A, 25 → Z, 26 → AA. */
function columnName(index: number): string {
  let n = index;
  let out = '';
  do {
    out = String.fromCharCode(65 + (n % 26)) + out;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return out;
}

/** «Розклад друк · 4 курс · стовпці R–T»: група займає 3 стовпці підряд, починаючи з C (DOMAIN §11). */
function groupLocation(code: string): string {
  const group = GROUPS.find((g) => g.code === code);
  if (!group) return 'Розклад друк';
  const index = GROUPS.filter((g) => g.course === group.course).findIndex((g) => g.code === code);
  const first = 2 + index * 3;
  return `Розклад друк · ${group.course} курс · стовпці ${columnName(first)}–${columnName(first + 2)}`;
}

const WEEKDAY_LOCATIVE = ['', 'понеділок', 'вівторок', 'середа', 'четвер', 'пʼятниця'];

function lessonLocation(lesson: { group: { code: string }; weekday: number; pair: number }): string {
  return `Розклад друк · ${lesson.group.code} · ${WEEKDAY_LOCATIVE[lesson.weekday] ?? ''}, ${lesson.pair} пара`;
}

// ───────────────────────── Таблиця розкладу ─────────────────────────

const teacherNamed = (shortName: string) => {
  const teacher = TEACHERS.find((t) => t.shortName === shortName);
  if (!teacher) throw new Error(`Мок-дані імпорту неузгоджені: немає викладача «${shortName}»`);
  return teacher;
};
const disciplineNamed = (shortName: string) => {
  const discipline = DISCIPLINES.find((d) => d.shortName === shortName);
  if (!discipline) throw new Error(`Мок-дані імпорту неузгоджені: немає дисципліни «${shortName}»`);
  return discipline;
};
const groupNamed = (code: string) => {
  const group = GROUPS.find((g) => g.code === code);
  if (!group) throw new Error(`Мок-дані імпорту неузгоджені: немає групи «${code}»`);
  return group;
};

/** Написання з таблиці, яких немає в каталозі (DOMAIN §10): кожне має кандидатів і чекає рішення. */
const TIMETABLE_TYPOS: ReadonlyArray<{ type: MappingType; raw: string; target: string; score: number; message: string }> = [
  {
    type: 'TEACHER',
    raw: 'Граболінська',
    target: 'Гарболінська',
    score: 0.94,
    message: 'Схоже на одруківку: переставлено літери. У таблиці трапляються обидва написання.',
  },
  { type: 'TEACHER', raw: 'Герасменко', target: 'Герасименко', score: 0.96, message: 'Схоже на одруківку: пропущено літеру «и».' },
  { type: 'DISCIPLINE', raw: 'Інфом.', target: 'Інф.', score: 0.86, message: 'Інша скорочена назва тієї самої дисципліни?' },
  { type: 'DISCIPLINE', raw: 'Вс.спц.', target: 'Вст.спц.', score: 0.9, message: 'Найімовірніше «Вступ до спеціальності».' },
];

/** «Вс.» обрізано у клітинці: два кандидати, рішення лише за людиною. */
const TRUNCATED = { raw: 'Вс.', candidates: [['Вс.іст.', 0.71], ['Вст.спц.', 0.62]] as const };

/** Коди груп, які нормалізатор виправив сам (DOMAIN §10): збіг є, але диспетчер має це побачити. */
const GROUP_FIXES: ReadonlyArray<{ raw: string; code: string; severity: ReportSeverity; message: string }> = [
  { raw: 'ІКІ1', code: '1КІ1', severity: 'WARN', message: 'Кирилична «І» замість одиниці: код виправлено на 1КІ1. Перевірте групу.' },
  { raw: '1ТК 1', code: '1ТК1', severity: 'INFO', message: 'Пробіл у коді прибрано.' },
  { raw: '4КИ1', code: '4КІ1', severity: 'INFO', message: '«И» замість «І» (російська розкладка): код виправлено.' },
];

function firstLessonOf(predicate: (l: (typeof TEMPLATE_LESSONS)[number]) => boolean) {
  return TEMPLATE_LESSONS.find(predicate);
}

function timetableReport(): ImportReportRow[] {
  const report: ImportReportRow[] = [];
  report.push(row('OK', 'FILE', 'Розклад 2025-2026.xlsx', 'Розклад друк', 'Аркуш «Розклад друк»: 4 курсові блоки, 56 груп, 1077 занять.'));
  for (const group of GROUPS) {
    const fix = GROUP_FIXES.find((f) => f.code === group.code);
    report.push(
      fix
        ? row(fix.severity, 'GROUP', fix.raw, groupLocation(group.code), fix.message, scored(group.id, group.code, 0.9))
        : row('OK', 'GROUP', group.code, groupLocation(group.code), null, scored(group.id, group.code, 1)),
    );
  }
  for (const teacher of TEACHERS) {
    const lesson = firstLessonOf((l) => l.teacher?.id === teacher.id);
    report.push(
      row('OK', 'TEACHER', teacher.shortName, lesson ? lessonLocation(lesson) : 'Розклад друк · рядок кураторів', null, scored(teacher.id, teacher.shortName, 1)),
    );
  }
  report.push(
    row(
      'INFO',
      'TEACHER',
      'Хорченко О.',
      'Розклад друк · 1КІ1',
      'Збіг за псевдонімом каталогу.',
      scored(teacherNamed('Харченко О.А.').id, 'Харченко О.А.', 1),
    ),
    row(
      'INFO',
      'TEACHER',
      'Поліщук Л.',
      'Розклад друк · 2КІ1',
      'Ініціали дописано з каталогу. «Поліщук М.» — інша людина, її не обʼєднано.',
      scored(teacherNamed('Поліщук Л.І.').id, 'Поліщук Л.І.', 0.97),
    ),
  );
  for (const discipline of DISCIPLINES) {
    const lesson = firstLessonOf((l) => l.discipline.id === discipline.id);
    report.push(
      row(
        discipline.needsReview ? 'INFO' : 'OK',
        'DISCIPLINE',
        discipline.shortName,
        lesson ? lessonLocation(lesson) : 'Розклад друк',
        discipline.needsReview ? 'Повна назва невідома: лишаємо скорочення й позначаємо «перевірити назву».' : null,
        scored(discipline.id, discipline.shortName, 1),
      ),
    );
  }
  report.push(
    row('INFO', 'DISCIPLINE', 'Укр.мова.', 'Розклад друк · 1 курс', 'Зайву крапку прибрано: збіг за псевдонімом.', scored(disciplineNamed('Укр.мова').id, 'Укр.мова', 1)),
    row('INFO', 'DISCIPLINE', 'Фк', 'Розклад друк · 1 курс', 'Регістр не враховано: збіг за псевдонімом.', scored(disciplineNamed('ФК').id, 'ФК', 1)),
  );
  for (const room of ROOMS) {
    report.push(row('OK', 'ROOM', room.name, 'Розклад друк', null, scored(room.id, room.name, 1)));
  }
  report.push(
    row('INFO', 'ROOM', 'С.З.', 'Розклад друк · 3 курс', 'Регістр і крапки не враховано: це спортзал «с.з.».', scored(ROOMS.find((r) => r.name === 'с.з.')?.id ?? '', 'с.з.', 1)),
  );
  for (const typo of TIMETABLE_TYPOS) {
    const target =
      typo.type === 'TEACHER' ? teacherNamed(typo.target) : disciplineNamed(typo.target);
    const lesson =
      typo.type === 'TEACHER'
        ? firstLessonOf((l) => l.teacher?.id === target.id)
        : firstLessonOf((l) => l.discipline.id === target.id);
    report.push(
      row('WARN', typo.type, typo.raw, lesson ? lessonLocation(lesson) : 'Розклад друк', typo.message, null, [
        scored(target.id, typo.target, typo.score),
      ]),
    );
  }
  report.push(
    row(
      'WARN',
      'DISCIPLINE',
      TRUNCATED.raw,
      'Розклад друк · 1 курс',
      'Назву обрізано в клітинці. Оберіть дисципліну або створіть нову.',
      null,
      TRUNCATED.candidates.map(([name, score]) => scored(disciplineNamed(name).id, name, score)),
    ),
  );
  report.push(
    row('INFO', 'LESSON', 'Крисак Л./Можаровська · 110/12', 'Розклад друк · 1ТК1 · понеділок, 1 пара', 'Дві підгрупи в одній клітинці: 1 — Крисак Л.В. (110), 2 — Можаровська О.Е. (12).'),
    row('INFO', 'LESSON', 'Харченко О.(п)', 'Розклад друк · 1КІ1', '«(п)» — заняття лише однієї підгрупи.'),
    row('OK', 'BELLS', 'дзвінки (2 зм)', 'Аркуш «дзвінки (2 зм)»', 'Профіль дзвінків: 8 пар пн–чт, 6 пар у пʼятницю.'),
  );
  return report;
}

/** Деякі пари записані з одруківками — так їх побачить і чернетка, і звіт. */
function rawTeacherName(shortName: string, index: number): string {
  if (shortName === 'Гарболінська' && index % 3 === 0) return 'Граболінська';
  if (shortName === 'Герасименко' && index % 4 === 0) return 'Герасменко';
  return shortName;
}

function timetableDraft(): TimetableDraft {
  const lessonCount = (pred: (l: (typeof TEMPLATE_LESSONS)[number]) => boolean) => TEMPLATE_LESSONS.filter(pred).length;
  const bells = BELL_PROFILES.find((p) => p.name === '2 зміни, 60 хв');
  return {
    sheet: 'Розклад друк',
    title: 'РОЗКЛАД ЗАНЯТЬ 2025- 2026 н.р.',
    semesterLabel: 'І семестр',
    groups: GROUPS.map((g) => ({
      code: g.code,
      raw: GROUP_FIXES.find((f) => f.code === g.code)?.raw ?? null,
      course: g.course,
      specialtyLetters: g.specialty?.label ?? '',
      number: g.number,
      shift: g.shift,
      subgroups: (g.subgroups ?? []).map((s) => s.number),
      homeRoom: g.homeRoom?.name ?? null,
      curator: g.curator?.shortName ?? null,
      location: groupLocation(g.code),
    })),
    teachers: TEACHERS.map((t) => ({
      shortName: t.shortName,
      displayName: t.displayName,
      variants: [t.shortName, ...TIMETABLE_TYPOS.filter((x) => x.type === 'TEACHER' && x.target === t.shortName).map((x) => x.raw)],
      lessonCount: lessonCount((l) => l.teacher?.id === t.id),
      needsReview: false,
    })),
    disciplines: DISCIPLINES.map((d) => ({
      shortName: d.shortName,
      name: d.name,
      needsReview: d.needsReview,
      variants: [d.shortName],
      lessonCount: lessonCount((l) => l.discipline.id === d.id),
    })),
    rooms: ROOMS.map((r) => ({ name: r.name, type: r.type, maxParallel: r.maxParallel, variants: [r.name] })),
    lessons: TEMPLATE_LESSONS.map((l, index) => ({
      weekday: l.weekday,
      pair: l.pair,
      parity: l.parity,
      groupCode: l.group.code,
      subgroup: l.subgroup,
      discipline: l.discipline.shortName,
      teacher: l.teacher ? rawTeacherName(l.teacher.shortName, index) : null,
      room: l.room?.name ?? null,
      location: lessonLocation(l),
    })),
    bellProfiles: bells ? [{ name: 'дзвінки (2 зм)', slots: bells.slots }] : [],
  };
}

// ───────────────────────── Фото розпорядження №35 ─────────────────────────

/** Рядки з фото seed/orders/order-035-2026-10-19.jpg — так, як їх розпізнає модель зору. */
const ORDER_35: OrderDraft = {
  number: 35,
  kind: 'ORDER',
  parentNumber: null,
  date: '2026-10-19',
  weekdayLabel: 'ПОНЕДІЛОК',
  parityLabel: 'ЗНАМЕННИК',
  rows: [
    ['1ТК1', '1-4', 'BLOCK', 'ЗУ', 'Ємельяненко', null, null, null, '204'],
    ['1ОП2', '4', 'ADDITIONAL', null, null, null, 'БЖ', 'Погосян', '403'],
    ['1ТК1', '1', 'CANCELLATION', 'Ін.мова', 'Крисак Л. (п.)', 'Відп.', null, null, null],
    ['1КІ2', '2', 'REPLACEMENT', 'Ін.мова', 'Крисак Л.', 'Відп.', 'Ін.мова', 'Прокопчук', '110'],
    ['1М', '3', 'REPLACEMENT', 'Ін.мова', 'Крисак Л.', 'Відп.', 'Ін.мова', 'Прокопчук', '110'],
    ['1ЕА2', '3', 'REPLACEMENT', 'ЕТ', 'Масловата', 'Зв.зам.', 'Культ.', 'Шароварська', '405'],
    ['2ОД', '4', 'ADDITIONAL', null, null, null, 'ІКТ', 'Вернигора', '309'],
    ['2ОД', '6', 'REPLACEMENT', 'ІМД', 'Масловата', 'Зв.зам.', 'ОД', 'Яцуба', '308'],
    ['2ОБ', '7', 'REPLACEMENT', 'ЕіФБ', 'Охота О.', 'Зв.зам.', 'ІКТ', 'Вернигора', '304'],
    ['2ОБ', '8', 'REPLACEMENT', 'ІМД', 'Масловата', 'Зв.зам.', 'ТБО', 'Фабіянська', '409'],
    ['2М', '8', 'REPLACEMENT', 'ЕіФБ', 'Охота О.', 'Зв.зам.', 'Укр.літ.', 'Кучинська', '303'],
    ['2М', '5', 'REPLACEMENT', 'ІМД', 'Масловата', 'Зв.зам.', 'Вс.іст.', 'Бадунь', '409'],
    ['2Ф', '7', 'REPLACEMENT', 'ІМД', 'Масловата', 'Зв.зам.', 'ЕіФБ', 'Кукурудзяк', '408'],
    ['3Ф', '4', 'CANCELLATION', 'Ін.мова', 'Крисак Л. (п.)', 'Відп.', null, null, null],
    ['4КІ1', '2', 'REPLACEMENT', 'СТП', 'Кренцін', 'Зв.зам.', 'НДКС', 'Непийвода', '6'],
    ['4КІ2', '4', 'REPLACEMENT', 'СТП', 'Кренцін', 'Зв.зам.', 'ОПЦЗ', 'Ганза', '6'],
    ['1ОП2', '4', 'ADDITIONAL', null, null, null, 'БЖ', 'Погосян', '403'],
  ].map(([groupCode, pairLabel, rowType, fromDiscipline, fromTeacher, reasonText, toDiscipline, toTeacher, roomText]) => ({
    section: 'CHANGES' as const,
    groupCode: groupCode as string,
    pairLabel: pairLabel as string,
    rowType: rowType as OrderDraft['rows'][number]['rowType'],
    fromDiscipline,
    fromTeacher,
    reasonText,
    toDiscipline,
    toTeacher,
    roomText,
  })),
  notes: [
    '2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.',
    '4ОП2 - Основи національного спротиву з 05.10.26р. по 16.10.26р. 401- аудиторія.',
    '3 курс групи 3Ф,3ОД, 3ЕП, 3ОБ, 3РТ,3ТК,3ЕА,3ВЕ,3ОП з 1.09.26р. по 31.10.26.– Навчаються за розкладом дистанційно.',
    'Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.',
    '4ЕА,4ВЕ,4РТ,4ОП навчаються очно з 01.09.26 по 31.10.26 р.',
  ],
  signatoryPosition: 'Заступник директора з НР',
  signatoryName: 'Микола ПОЛІЩУК',
  warnings: ['Рядок «1ОП2, 4 пара» повторюється двічі (рядки 2 і 17).', 'Розділ «ЗАМІНА АУДИТОРІЙ» порожній.'],
};

function orderReport(): ImportReportRow[] {
  const report: ImportReportRow[] = [
    row('OK', 'FILE', 'розпорядження-35.jpg', 'Фото', 'Розпізнано моделлю зору: заголовок, 17 рядків, 5 приміток, підпис.'),
    row('OK', 'ORDER', 'РОЗПОРЯДЖЕННЯ №35', 'Заголовок', 'На 19 жовтня 2026 р., ПОНЕДІЛОК – ЗНАМЕННИК (збігається з календарем: 8 тиждень).'),
    row('WARN', 'ORDER', '1ОП2 · 4 пара', 'Таблиця, рядки 2 і 17', 'Рядок повторюється двічі — імпортуємо один раз.'),
  ];
  const teachersInOrder = new Map<string, string>([
    ['Крисак Л.', 'Крисак Л.В.'],
    ['Прокопчук', 'Прокопчук'],
    ['Масловата', 'Масловата Д.Р.'],
    ['Шароварська', 'Шароварська С.П.'],
    ['Вернигора', 'Вернигора'],
    ['Яцуба', 'Яцуба'],
    ['Охота О.', 'Охота О.Я.'],
    ['Фабіянська', 'Фабіянська'],
    ['Кучинська', 'Кучинська О.Г.'],
    ['Бадунь', 'Бадунь'],
    ['Кукурудзяк', 'Кукурудзяк'],
    ['Кренцін', 'Кренцін'],
    ['Непийвода', 'Непийвода'],
    ['Ганза', 'Ганза А.І.'],
    ['Погосян', 'Погосян'],
  ]);
  for (const [raw, shortName] of teachersInOrder) {
    const teacher = teacherNamed(shortName);
    const exact = raw === shortName;
    report.push(
      row(exact ? 'OK' : 'INFO', 'TEACHER', raw, 'Таблиця', exact ? null : `Ініціали дописано з каталогу: ${shortName}.`, scored(teacher.id, shortName, exact ? 1 : 0.97)),
    );
  }
  report.push(row('INFO', 'TEACHER', 'Ємельяненко', 'Таблиця, рядок 1', 'Немає в каталозі (для інформації: розпорядження зберігається як текст, каталог не змінюється).'));
  for (const name of ['Ін.мова', 'ЕТ', 'Культ.', 'ІКТ', 'ІМД', 'ОД', 'ЕіФБ', 'ТБО', 'Укр.літ.', 'Вс.іст.', 'СТП', 'ОПЦЗ', 'БЖ']) {
    const discipline = disciplineNamed(name);
    report.push(row('OK', 'DISCIPLINE', name, 'Таблиця', null, scored(discipline.id, name, 1)));
  }
  report.push(
    row('INFO', 'DISCIPLINE', 'НДКС', 'Таблиця, рядок 15', 'Збіг за псевдонімом каталогу: НДЕКСМ.', scored(disciplineNamed('НДЕКСМ').id, 'НДЕКСМ', 1)),
    row('INFO', 'DISCIPLINE', 'ЗУ', 'Таблиця, рядок 1', 'Немає в каталозі (для інформації: розпорядження зберігається як текст, каталог не змінюється).'),
  );
  for (const code of ['1ТК1', '1ОП2', '1КІ2', '1М', '1ЕА2', '2ОД', '2ОБ', '2М', '2Ф', '3Ф', '4КІ1', '4КІ2']) {
    const group = groupNamed(code);
    report.push(row('OK', 'GROUP', code, 'Таблиця', null, scored(group.id, code, 1)));
  }
  for (const name of ['204', '403', '110', '405', '309', '308', '304', '409', '303', '408', '6']) {
    const room = ROOMS.find((r) => r.name === name);
    if (room) report.push(row('OK', 'ROOM', name, 'Таблиця', null, scored(room.id, name, 1)));
  }
  report.push(row('INFO', 'ORDER', 'ЗАМІНА АУДИТОРІЙ', 'Таблиця', 'Розділ порожній.'));
  return report;
}

// ───────────────────────── Програма дисципліни ─────────────────────────

/** Таблиця «Структура навчальної дисципліни» з seed/programs/program-economics-it-projects.docx. */
const PROGRAM_ECONOMICS: ProgramDraft = {
  disciplineName: 'Економіка та управління ІТ-проєктами',
  disciplineRaw: 'Економіка та  управління ІТ-проєктами',
  cycleCommission: null,
  hours: { total: 150, classroom: 60, selfStudy: 90, lectures: 30, practice: null, labs: 20, seminars: 10, consultations: null, exam: true, credit: false },
  semester: 7,
  hoursPerWeek: 3,
  credits: 5,
  source: 'TABLE',
  location: 'таблиця 2',
};

function programReport(groupIds: readonly Uuid[], semesterId: Uuid | null): ImportReportRow[] {
  const discipline = disciplineNamed('ЕУ');
  const teacher = teacherNamed('Алексєєнко');
  const semester = semesterId ? semesters.find(semesterId) : undefined;
  const report: ImportReportRow[] = [
    row('OK', 'FILE', 'Програма ЕУІТП.docx', 'Документ', 'Знайдено таблицю «Структура навчальної дисципліни загальна й за семестрами».'),
    row('OK', 'DISCIPLINE', 'Економіка та управління ІТ-проєктами', 'Титульна сторінка', 'Повна назва збігається з каталогом.', scored(discipline.id, 'ЕУ', 0.98)),
    row('INFO', 'TEACHER', 'Олександра АЛЕКСЄЄНКО', 'Розробники', 'Розробник програми, викладач каталогу.', scored(teacher.id, 'Алексєєнко', 0.96)),
    row('OK', 'HOURS', '150 = 60 + 90', 'таблиця 2', 'Усього годин = на заняттях + самостійна робота.'),
    row('OK', 'HOURS', '60 = 30 + 20 + 10', 'таблиця 2', 'На заняттях = лекції + лабораторні + семінарські.'),
    row('INFO', 'HOURS', 'СРС: 90', 'таблиця 2', 'У тому числі 30 год — підготовка до екзамену.'),
    row('INFO', 'HOURS', 'Семестр 7', 'таблиця 2', 'Семестр із програми — лише довідка: години привʼязано до вибраних груп і семестру.'),
  ];
  if (semester) {
    const weeksNeeded = (PROGRAM_ECONOMICS.hours?.classroom ?? 0) / (PROGRAM_ECONOMICS.hoursPerWeek ?? 1);
    if (weeksNeeded > semester.teachingWeeks) {
      report.push(
        row(
          'WARN',
          'HOURS',
          '60 год ÷ 3 год/тиж',
          'таблиця 1',
          `Аудиторних 60 год за 3 год на тиждень — це ${weeksNeeded} тижнів, а в семестрі «${semester.name}» їх ${semester.teachingWeeks}: не вистачить ${60 - 3 * semester.teachingWeeks} год.`,
        ),
      );
    }
  }
  for (const id of groupIds) {
    const group = groups.find(id);
    if (group) report.push(row('OK', 'GROUP', group.code, 'Параметри завантаження', 'Години буде записано в навантаження групи.', scored(group.id, group.code, 1)));
  }
  return report;
}

// ───────────────────────── Навчальний план ─────────────────────────

/** Рядки 7 семестру навчального плану КІ 2023 (ред. 2025), DOMAIN §4: 22 тижні, 31 год на тиждень. */
const CURRICULUM_KI_7: CurriculumDraft = {
  semester: 7,
  weeks: 22,
  rows: [
    ['Компʼютерні мережі', 4],
    ['Економіка та управління ІТ-проєктами', 3],
    ['Надійність, діагностика та експлуатація компʼютерних систем і мереж', 2],
    ['Системне програмування', 2],
    ['Охорона праці та цивільний захист', 2],
    ['Соціологія', 2],
    ['Іноземна мова', 2],
    ['Українська мова (за професійним спрямуванням)', 2],
    ['Фізичне виховання', 2],
  ].map(([discipline, hoursPerWeek]) => ({
    discipline: discipline as string,
    hoursPerWeek: hoursPerWeek as number,
    hoursPerSemester: (hoursPerWeek as number) * 22,
    totalHours: null,
    classroomHours: (hoursPerWeek as number) * 22,
    lectures: discipline === 'Надійність, діагностика та експлуатація компʼютерних систем і мереж' ? 24 : null,
    practice: null,
    labs: discipline === 'Надійність, діагностика та експлуатація компʼютерних систем і мереж' ? 20 : null,
    controlForm: 'UNKNOWN' as const,
  })),
  totalHoursPerWeek: 31,
  warnings: ['Сума рядків — 21 год на тиждень, а в підсумку плану — 31: частину рядків на скані не розпізнано.'],
};

const CURRICULUM_MATCHES: ReadonlyArray<readonly [string, string | null]> = [
  ['Компʼютерні мережі', 'КМ'],
  ['Економіка та управління ІТ-проєктами', 'ЕУ'],
  ['Надійність, діагностика та експлуатація компʼютерних систем і мереж', 'НДЕКСМ'],
  ['Системне програмування', 'СП'],
  ['Охорона праці та цивільний захист', 'ОПЦЗ'],
  ['Соціологія', null],
  ['Іноземна мова', 'Ін.мова'],
  ['Українська мова (за професійним спрямуванням)', 'Укр.мов.'],
  ['Фізичне виховання', 'ФВ'],
];

function curriculumReport(): ImportReportRow[] {
  const report: ImportReportRow[] = [
    row('OK', 'FILE', 'навчальний-план-КІ.pdf', 'Сторінка 3', 'Розпізнано моделлю зору: 7 семестр, 22 тижні.'),
    row('WARN', 'CURRICULUM', '21 / 31 год на тиждень', 'Сторінка 3', CURRICULUM_KI_7.warnings[0] ?? null),
  ];
  for (const [name, shortName] of CURRICULUM_MATCHES) {
    if (shortName) {
      const discipline = disciplineNamed(shortName);
      report.push(row(shortName === 'Укр.мов.' ? 'WARN' : 'OK', 'DISCIPLINE', name, 'Сторінка 3', shortName === 'Укр.мов.' ? 'Збіг за повною назвою; у каталозі це «Укр.мов.» — перевірте.' : null, scored(discipline.id, shortName, shortName === 'Укр.мов.' ? 0.88 : 0.97)));
    } else {
      report.push(row('INFO', 'DISCIPLINE', name, 'Сторінка 3', 'У каталозі немає: буде створено нову дисципліну.'));
    }
  }
  return report;
}

// ───────────────────────── Завдання ─────────────────────────

const KIND_HINT: Readonly<Record<ImportKind, string>> = {
  TIMETABLE_XLSX: 'Для розкладу потрібна таблиця Excel (XLSX).',
  DISCIPLINE_PROGRAM: 'Програму дисципліни приймаємо у DOCX, DOC або PDF.',
  CURRICULUM: 'Навчальний план — PDF-скан, фото (JPG, PNG) або XLSX.',
  ORDER_DOCUMENT: 'Розпорядження — DOCX, PDF або фото (JPG, PNG).',
};

interface ParseResult {
  report: ImportReportRow[];
  draft: unknown;
  ai: NonNullable<ImportJob['ai']> | null;
}

/** ДЕМО-витрати: токени й вартість виклику моделі зору для одного документа. */
const visionUsage = (inputTokens: number, outputTokens: number) => ({
  model: VISION_MODEL,
  inputTokens,
  outputTokens,
  costUsd: Math.round(((inputTokens * 3 + outputTokens * 15) / 1_000_000) * 10_000) / 10_000,
});

function parse(kind: ImportKind, fileName: string, semesterId: Uuid | null, options: ImportOptions | null): ParseResult {
  switch (kind) {
    case 'TIMETABLE_XLSX':
      return { report: timetableReport(), draft: timetableDraft(), ai: null };
    case 'ORDER_DOCUMENT':
      return { report: orderReport(), draft: ORDER_35, ai: usesVision(kind, fileName) ? visionUsage(3850, 1240) : null };
    case 'DISCIPLINE_PROGRAM':
      return {
        report: programReport(groupIdsOf(options), semesterId),
        draft: { ...PROGRAM_ECONOMICS, source: extensionOf(fileName) === 'pdf' ? 'PDF_TEXT' : 'TABLE' },
        ai: null,
      };
    case 'CURRICULUM':
      return { report: curriculumReport(), draft: CURRICULUM_KI_7, ai: usesVision(kind, fileName) ? visionUsage(6120, 1730) : null };
  }
}

const statusAfterParse = (report: readonly ImportReportRow[]): ImportJobStatus =>
  summaryOf(report).conflicts > 0 ? 'NEEDS_REVIEW' : 'READY';

function parsedJob(
  id: Uuid,
  kind: ImportKind,
  fileName: string,
  createdAt: string,
  semesterId: Uuid | null,
  options: ImportOptions | null,
): StoredJob {
  const parsed = parse(kind, fileName, semesterId, options);
  return {
    id,
    kind,
    fileName,
    fileType: fileTypeOf(fileName),
    fileSize: 184_320,
    status: statusAfterParse(parsed.report),
    createdAt,
    updatedAt: createdAt,
    createdBy: 'admin',
    summary: summaryOf(parsed.report),
    report: parsed.report,
    draft: parsed.draft,
    ai: parsed.ai,
    error: null,
    semesterId,
    options,
    mappings: [],
    result: null,
    pendingPolls: 0,
    aiAt: parsed.ai ? createdAt : null,
  };
}

/** ДЕМО-завдання: різні стани в списку. Час — відносно «зараз», вартість ШІ — оцінка. */
function seedJobs(): StoredJob[] {
  const semester = activeSemester();
  const fourthCourseKi = ['4КІ1', '4КІ2', '4КІ3'].map((code) => groupNamed(code).code);
  const timetable = parsedJob(mockId('import-job', 'timetable-2025-26'), 'TIMETABLE_XLSX', 'Розклад 2025-2026.xlsx', hoursAgo(20), semester.id, null);
  const order = parsedJob(mockId('import-job', 'order-35-photo'), 'ORDER_DOCUMENT', 'розпорядження-35.jpg', hoursAgo(2), null, null);
  const program = parsedJob(mockId('import-job', 'program-economics'), 'DISCIPLINE_PROGRAM', 'Програма ЕУІТП.docx', hoursAgo(47), semester.id, {
    groupCodes: fourthCourseKi,
  });
  const failed: StoredJob = {
    id: mockId('import-job', 'curriculum-ki-budget'),
    kind: 'CURRICULUM',
    fileName: 'навчальний-план-КІ.pdf',
    fileType: 'PDF',
    fileSize: 2_411_520,
    status: 'FAILED',
    createdAt: hoursAgo(75),
    updatedAt: hoursAgo(75),
    createdBy: 'admin',
    summary: { recognized: 0, unknown: 0, conflicts: 0, warnings: 0 },
    report: [row('ERROR', 'FILE', 'навчальний-план-КІ.pdf', 'Документ', 'Скан потребує моделі зору, а денний бюджет ШІ вичерпано.')],
    draft: null,
    ai: null,
    error: `Денний бюджет ШІ ($${DAILY_BUDGET_USD.toFixed(2)}) вичерпано. Завантажте файл ще раз завтра або збільште бюджет у налаштуваннях.`,
    semesterId: semester.id,
    options: { semesterNumber: 7, groupCodes: ['4КІ1'] },
    mappings: [],
    result: null,
    pendingPolls: 0,
    aiAt: null,
  };
  return [timetable, order, program, failed];
}

const jobs = createMockCollection<StoredJob>('Завдання імпорту', seedJobs);

/** Чи можна застосувати — як ImportJob.canApply() importer (NEEDS_REVIEW з error — повернене core). */
function canApply(job: StoredJob): boolean {
  if (job.status === 'READY') return true;
  const intact = job.draft !== null && job.draft !== undefined && summaryOf(job.report).conflicts === 0;
  return (job.status === 'FAILED' || (job.status === 'NEEDS_REVIEW' && Boolean(job.error))) && intact;
}

/** Як ImportHandler.creates: розпорядження нічого не створює, програма й план — лише дисципліни. */
function createsEntity(kind: ImportKind, type: MappingType): boolean {
  if (kind === 'ORDER_DOCUMENT') return false;
  if (kind === 'DISCIPLINE_PROGRAM' || kind === 'CURRICULUM') return type === 'DISCIPLINE';
  return true;
}

const OPTIONS_LOCATION = 'параметри імпорту';

/** Як LoadApplier.readiness: без груп програму чи план не застосувати. */
function withReadiness(kind: ImportKind, report: readonly ImportReportRow[], options: ImportOptions | null): ImportReportRow[] {
  const rest = report.filter((r) => !(r.severity === 'ERROR' && r.location === OPTIONS_LOCATION));
  if ((kind === 'DISCIPLINE_PROGRAM' || kind === 'CURRICULUM') && !options?.groupCodes?.length) {
    return [row('ERROR', 'OPTIONS', '', OPTIONS_LOCATION, 'Вкажіть групи, яким додати навантаження (параметри імпорту).'), ...rest];
  }
  return rest;
}

/** Відповідь як ImportJobDto (NON_NULL); `list` — елемент списку: порожній звіт, без чернетки, параметрів і результату. */
function toDto(job: StoredJob, list = false): ImportJob {
  const dto: ImportJob = {
    id: job.id,
    kind: job.kind,
    fileName: job.fileName,
    fileType: job.fileType,
    fileSize: job.fileSize,
    status: job.status,
    createdAt: job.createdAt,
    updatedAt: job.updatedAt,
    createdBy: job.createdBy,
    summary: job.summary,
    report: list ? [] : job.report,
    canApply: canApply(job),
  };
  if (job.semesterId) dto.semesterId = job.semesterId;
  if (job.ai) dto.ai = job.ai;
  if (job.error) dto.error = job.error;
  if (!list) {
    // мок не моделює паралельних правок: версія завжди 0 (importer рахує її як @Version)
    dto.version = 0;
    if (job.draft !== null && job.draft !== undefined) dto.draft = job.draft;
    if (job.options) dto.options = job.options;
    dto.mappings = job.mappings;
    if (job.result) dto.result = job.result;
  }
  return dto;
}

/** Групи з параметрів (importer приймає коди груп) → id каталогу для звіту й навантаження. */
function groupIdsOf(options: ImportOptions | null): Uuid[] {
  return (options?.groupCodes ?? []).flatMap((code) => {
    const group = groups.all().find((g) => g.code === code);
    return group ? [group.id] : [];
  });
}

// ───────────────────────── Рішення щодо назв ─────────────────────────

const ENTITY_TYPES: readonly MappingType[] = ['TEACHER', 'DISCIPLINE', 'GROUP', 'ROOM'];

function targetLabel(type: MappingType, id: Uuid): string | null {
  switch (type) {
    case 'TEACHER':
      return teachers.find(id)?.shortName ?? null;
    case 'DISCIPLINE':
      return disciplines.find(id)?.shortName ?? null;
    case 'GROUP':
      return groups.find(id)?.code ?? null;
    case 'ROOM':
      return rooms.find(id)?.name ?? null;
  }
}

function applyMappings(job: StoredJob, mappings: unknown): StoredJob {
  if (job.status !== 'NEEDS_REVIEW' && job.status !== 'READY' && !(job.status === 'FAILED' && job.draft != null)) {
    throw conflict(`Рішення можна змінювати лише до застосування (зараз: ${job.status}).`);
  }
  if (!Array.isArray(mappings)) throw badRequest('Очікується список рішень.', [{ field: 'mappings', message: 'Очікується масив.' }]);
  const errors: Array<{ field: string; message: string }> = [];
  const list = mappings as ImportMapping[];
  list.forEach((m, i) => {
    const field = `mappings[${i}]`;
    if (!ENTITY_TYPES.includes(m.type)) errors.push({ field: `${field}.type`, message: 'Тип — TEACHER, DISCIPLINE, GROUP або ROOM.' });
    if (!job.report.some((r) => r.type === m.type && r.raw === m.raw)) {
      errors.push({ field: `${field}.raw`, message: `У звіті немає «${m.raw}».` });
    }
    const hasTarget = Boolean(m.targetId);
    const hasNew = m.createNew !== null && m.createNew !== undefined;
    if (hasTarget === hasNew) errors.push({ field, message: 'Оберіть наявний запис або створення нового — щось одне.' });
    if (hasTarget && !targetLabel(m.type, m.targetId as Uuid)) errors.push({ field: `${field}.targetId`, message: 'Запис каталогу не знайдено.' });
    if (m.addAlias && !(m.type === 'TEACHER' || m.type === 'DISCIPLINE')) {
      errors.push({ field: `${field}.addAlias`, message: 'Псевдоніми зберігаються лише для викладачів і дисциплін.' });
    }
    if (m.addAlias && !hasTarget) errors.push({ field: `${field}.addAlias`, message: 'Псевдонім додають до наявного запису.' });
    if (hasNew && !createsEntity(job.kind, m.type)) {
      errors.push({ field: `${field}.createNew`, message: 'Цей імпорт не створює таких записів — зіставте значення з наявним записом каталогу.' });
    }
  });
  if (errors.length > 0) throw badRequest('Перевірте рішення.', errors);
  const merged = new Map<string, ImportMapping>((job.mappings ?? []).map((m) => [`${m.type}|${m.raw}`, m]));
  for (const m of list) merged.set(`${m.type}|${m.raw}`, { ...m, addAlias: Boolean(m.addAlias) });
  const report = job.report.map((r) => {
    const decision = merged.get(`${r.type}|${r.raw}`);
    if (!decision) return r;
    if (decision.targetId) {
      const label = targetLabel(decision.type, decision.targetId) ?? '';
      return {
        ...r,
        severity: 'OK' as const,
        matched: { id: decision.targetId, label, score: 1 },
        message: decision.addAlias ? `Зіставлено вручну: ${label}; написання «${r.raw}» стане псевдонімом.` : `Зіставлено вручну: ${label}.`,
      };
    }
    const { matched: _matched, ...rest } = r;
    return { ...rest, severity: 'INFO' as const, message: 'Буде створено новий запис каталогу.' };
  });
  return {
    ...job,
    mappings: [...merged.values()],
    report,
    summary: summaryOf(report),
    status: statusAfterParse(report),
    updatedAt: new Date().toISOString(),
  };
}

// ───────────────────────── Застосування ─────────────────────────

function createFromImport(type: MappingType, raw: string): boolean {
  switch (type) {
    case 'TEACHER': {
      if (teachers.all().some((t) => t.shortName === raw)) return false;
      const lastName = raw.split(' ')[0] ?? raw;
      teachers.insert({
        id: mockId('teacher', `import:${raw}`),
        lastName,
        firstName: null,
        middleName: null,
        shortName: raw,
        displayName: lastName,
        cycleCommission: null,
        employment: 'STAFF',
        email: null,
        active: true,
        needsReview: true,
        linked: false,
        version: 0,
      });
      return true;
    }
    case 'DISCIPLINE': {
      if (disciplines.all().some((d) => d.shortName === raw || d.name === raw)) return false;
      const known: Readonly<Record<string, string>> = { ЗУ: 'Захист України' };
      disciplines.insert({
        id: mockId('discipline', `import:${raw}`),
        name: known[raw] ?? raw,
        shortName: raw,
        color: null,
        active: true,
        needsReview: !(raw in known),
        version: 0,
      });
      return true;
    }
    case 'ROOM': {
      if (rooms.all().some((r) => r.name === raw)) return false;
      const type: RoomType = 'CLASSROOM';
      rooms.insert({ id: mockId('room', `import:${raw}`), name: raw, type, building: null, capacity: null, maxParallel: 1, active: true, version: 0 });
      return true;
    }
    case 'GROUP':
      // групи з невідомою спеціальністю core створити не може — у демо таких рядків немає
      return false;
  }
}

interface LoadFromImport {
  disciplineId: Uuid;
  hoursPerWeek: number;
  plannedHours: number;
  controlForm: 'EXAM' | 'CREDIT' | 'NONE';
  source: 'PROGRAM' | 'CURRICULUM';
  note: string;
}

/** Записує години в навантаження вибраних груп (як POST /api/admin/import/load): оновлює наявний запис або створює. */
function upsertLoad(job: StoredJob, entries: readonly LoadFromImport[]): { created: number; updated: number } {
  const semester = job.semesterId ? semesters.find(job.semesterId) : activeSemester();
  if (!semester) throw problem({ status: 422, slug: 'business-rule', code: 'IMPORT_SEMESTER_MISSING', detail: 'Семестр імпорту не знайдено.' });
  let created = 0;
  let updated = 0;
  for (const groupId of groupIdsOf(job.options)) {
    const group = groups.find(groupId);
    if (!group) continue;
    for (const entry of entries) {
      const discipline = disciplines.find(entry.disciplineId);
      if (!discipline) continue;
      const fields = {
        hoursPerWeek: entry.hoursPerWeek,
        plannedHours: entry.plannedHours,
        controlForm: entry.controlForm,
        source: entry.source,
        pattern: loadPattern(entry.hoursPerWeek),
        note: entry.note,
      };
      const existing = loadItems
        .all()
        .find((i) => i.semesterId === semester.id && i.group.id === groupId && i.discipline.id === discipline.id && !i.splitSubgroups);
      if (existing) {
        loadItems.update(existing.id, (current) => ({ ...current, ...fields, version: current.version + 1 }));
        updated += 1;
      } else {
        loadItems.insert({
          id: mockId('load-item', `${semester.id}|${groupId}|${discipline.id}|MIXED|false`),
          semesterId: semester.id,
          group: groupRefOf(group),
          discipline: disciplineRefOf(discipline),
          lessonKind: 'MIXED',
          splitSubgroups: false,
          teachers: [],
          requiredRoomType: null,
          preferredRoom: null,
          streamId: null,
          version: 0,
          ...fields,
        });
        created += 1;
      }
    }
  }
  return { created, updated };
}

/** Програма → навантаження вибраних груп: план = години на заняттях, форма контролю з таблиці. */
function applyProgram(job: StoredJob): { created: number; updated: number } {
  const draft = job.draft as ProgramDraft;
  const discipline = disciplines.all().find((d) => d.name === draft.disciplineName);
  if (!discipline) {
    throw problem({ status: 422, slug: 'business-rule', code: 'IMPORT_DISCIPLINE_MISSING', detail: `Дисципліни «${draft.disciplineName ?? '—'}» немає в каталозі.` });
  }
  const hoursPerWeek = job.options?.hoursPerWeek ?? draft.hoursPerWeek ?? 2;
  const planned = draft.hours?.classroom ?? 0;
  const { created, updated } = upsertLoad(job, [
    {
      disciplineId: discipline.id,
      hoursPerWeek,
      plannedHours: planned,
      controlForm: draft.hours?.exam ? 'EXAM' : draft.hours?.credit ? 'CREDIT' : 'NONE',
      source: 'PROGRAM',
      note: `Програма дисципліни: ${planned} год на заняттях (${draft.hours?.lectures ?? 0} лекц., ${draft.hours?.labs ?? 0} лаб., ${draft.hours?.seminars ?? 0} сем.).`,
    },
  ]);
  return { created, updated };
}

/** Навчальний план → навантаження вибраних груп за рядками, що мають збіг у каталозі. */
function applyCurriculum(job: StoredJob): { created: number; updated: number } {
  const draft = job.draft as CurriculumDraft;
  const entries: LoadFromImport[] = [];
  for (const planRow of draft.rows) {
    const match = job.report.find((r) => r.type === 'DISCIPLINE' && r.raw === planRow.discipline);
    const disciplineId =
      match?.matched?.id ?? disciplines.all().find((d) => d.shortName === planRow.discipline || d.name === planRow.discipline)?.id;
    if (!disciplineId || !planRow.hoursPerWeek) continue;
    entries.push({
      disciplineId,
      hoursPerWeek: planRow.hoursPerWeek,
      plannedHours: planRow.hoursPerSemester ?? 0,
      controlForm: planRow.controlForm === 'UNKNOWN' ? 'NONE' : planRow.controlForm,
      source: 'CURRICULUM',
      note: `Навчальний план, ${draft.semester} семестр: ${planRow.hoursPerSemester ?? '—'} год.`,
    });
  }
  return upsertLoad(job, entries);
}

function finishApply(job: StoredJob): StoredJob {
  // спершу каталог: псевдоніми з рішень і нові записи для назв без збігу
  let aliases = 0;
  for (const mapping of job.mappings ?? []) {
    if (mapping.targetId && mapping.addAlias && (mapping.type === 'TEACHER' || mapping.type === 'DISCIPLINE')) {
      ensureAlias(mapping.type, mapping.targetId, mapping.raw);
      aliases += 1;
    }
  }
  let createdEntities = 0;
  for (const r of job.report) {
    if ((r.type === 'TEACHER' || r.type === 'DISCIPLINE' || r.type === 'ROOM' || r.type === 'GROUP') && !r.matched) {
      if (createFromImport(r.type, r.raw)) createdEntities += 1;
    }
  }
  // результат — відповіді core за кроками, як їх зберігає importer (catalog, timetable, load, changes)
  // як CatalogImportRun у core: новий запис каталогу й новий псевдонім — обидва в `created`
  const catalog: ImportApplyResult['catalog'] =
    createdEntities + aliases === 0 ? { skipped: true } : { created: createdEntities + aliases, updated: 0, unchanged: 0, ids: {}, warnings: [] };
  let result: ImportApplyResult;
  switch (job.kind) {
    case 'TIMETABLE_XLSX': {
      const lessons = Array.isArray((job.draft as TimetableDraft | null)?.lessons) ? (job.draft as TimetableDraft).lessons.length : 0;
      result = {
        catalog,
        timetable: {
          versionId: mockId('timetable-version', `import:${job.id}`),
          name: job.options?.versionName ?? `Імпорт з «${job.fileName}»`,
          lessonCount: lessons,
          hardConflicts: 0,
          softWarnings: 0,
        },
      };
      break;
    }
    case 'ORDER_DOCUMENT': {
      const draft = job.draft as OrderDraft;
      // core ще не має POST /api/admin/import/changes (integration.md §5.15) — мок лише рахує рядки
      result = { changes: { date: draft.date, rows: draft.rows.length } };
      break;
    }
    case 'DISCIPLINE_PROGRAM': {
      const load = applyProgram(job);
      result = { catalog, load: { ...load, unchanged: 0, ids: {} } };
      break;
    }
    case 'CURRICULUM': {
      const load = applyCurriculum(job);
      result = { catalog, load: { ...load, unchanged: 0, ids: {} } };
      break;
    }
  }
  return { ...job, status: 'APPLIED', result, updatedAt: new Date().toISOString() };
}

/** Крок стану за одне опитування. */
function advance(job: StoredJob): StoredJob {
  if (job.pendingPolls > 0) {
    const pendingPolls = job.pendingPolls - 1;
    if (pendingPolls > 0) return jobs.update(job.id, (current) => ({ ...current, pendingPolls }));
    switch (job.status) {
      case 'UPLOADED':
        return jobs.update(job.id, (current) => ({ ...current, status: 'PARSING', pendingPolls: 2 }));
      case 'PARSING': {
        const parsed = parsedJob(job.id, job.kind, job.fileName, job.createdAt, job.semesterId ?? null, job.options ?? null);
        return jobs.update(job.id, () => ({ ...parsed, aiAt: parsed.ai ? new Date().toISOString() : null }));
      }
      case 'APPLYING':
        return jobs.update(job.id, (current) => ({ ...finishApply(current), pendingPolls: 0 }));
      case 'NEEDS_REVIEW':
      case 'READY':
      case 'APPLIED':
      case 'FAILED':
        return jobs.update(job.id, (current) => ({ ...current, pendingPolls: 0 }));
    }
  }
  return job;
}

// ───────────────────────── Витрати ШІ ─────────────────────────

function aiUsageToday(): AiUsageDto {
  const today = kyivClock().date;
  const spent = jobs.all().filter((j) => j.ai && j.aiAt && kyivClock(new Date(j.aiAt)).date === today);
  return {
    today: {
      calls: spent.length,
      inputTokens: spent.reduce((sum, j) => sum + (j.ai?.inputTokens ?? 0), 0),
      outputTokens: spent.reduce((sum, j) => sum + (j.ai?.outputTokens ?? 0), 0),
      costUsd: Math.round(spent.reduce((sum, j) => sum + (j.ai?.costUsd ?? 0), 0) * 10_000) / 10_000,
    },
    dailyBudgetUsd: DAILY_BUDGET_USD,
    remainingUsd: Math.max(0, Math.round((DAILY_BUDGET_USD - spent.reduce((sum, j) => sum + (j.ai?.costUsd ?? 0), 0)) * 10_000) / 10_000),
    model: { text: TEXT_MODEL, vision: VISION_MODEL },
    enabled: true,
  };
}

// ───────────────────────── Обробники ─────────────────────────

const OPTION_KEYS = ['sheet', 'versionName', 'semesterNumber', 'groupCodes', 'hoursPerWeek'] as const;

/**
 * Параметри завантаження — як JobOptions.from в importer: лише відомі ключі (інший → 400 на `options.<ключ>`),
 * рядки до 100 символів, семестр плану 1..8, коди груп — масив рядків, години 0 < x ≤ 40.
 */
function parseOptions(value: unknown, errors: Array<{ field: string; message: string }>): ImportOptions | null {
  if (value === null) return null;
  if (typeof value !== 'object' || Array.isArray(value)) {
    errors.push({ field: 'options', message: "Параметри мають бути JSON-об'єктом." });
    return null;
  }
  const raw = value as Record<string, unknown>;
  const options: ImportOptions = {};
  for (const key of Object.keys(raw)) {
    if (!(OPTION_KEYS as readonly string[]).includes(key)) {
      errors.push({ field: `options.${key}`, message: `Невідомий параметр. Допустимі: ${[...OPTION_KEYS].sort().join(', ')}.` });
    }
  }
  for (const key of ['sheet', 'versionName'] as const) {
    const text = raw[key];
    if (text === undefined || text === null) continue;
    if (typeof text !== 'string' || !text.trim() || text.length > 100) errors.push({ field: `options.${key}`, message: 'Непорожній рядок до 100 символів.' });
    else options[key] = text.trim();
  }
  const semester = raw.semesterNumber;
  if (semester !== undefined && semester !== null) {
    if (typeof semester !== 'number' || !Number.isInteger(semester) || semester < 1 || semester > 8) {
      errors.push({ field: 'options.semesterNumber', message: 'Номер семестру навчального плану — ціле число від 1 до 8.' });
    } else options.semesterNumber = semester;
  }
  const codes = raw.groupCodes;
  if (codes !== undefined && codes !== null) {
    if (!Array.isArray(codes) || codes.some((code) => typeof code !== 'string' || !groups.all().some((g) => g.code === code))) {
      errors.push({ field: 'options.groupCodes', message: 'Коди груп — масив рядків, наприклад ["4КІ1", "4КІ2"].' });
    } else options.groupCodes = [...new Set(codes as string[])];
  }
  const hours = raw.hoursPerWeek;
  if (hours !== undefined && hours !== null) {
    if (typeof hours !== 'number' || hours <= 0 || hours > 40) errors.push({ field: 'options.hoursPerWeek', message: 'Годин на тиждень — додатне число не більше 40.' });
    else options.hoursPerWeek = hours;
  }
  return options;
}

/** Файл із multipart: перевіряємо форму, а не клас (у Vitest File з jsdom і з Node — різні класи). */
function isUploadedFile(value: FormDataEntryValue | null): value is File {
  return typeof value === 'object' && value !== null && typeof value.name === 'string' && typeof value.size === 'number';
}

async function readUpload(request: Request): Promise<{ file: File; kind: ImportKind; semesterId: Uuid | null; options: ImportOptions | null }> {
  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    throw badRequest('Очікується multipart/form-data з файлом.');
  }
  const file = form.get('file');
  const kind = form.get('kind');
  const semesterId = form.get('semesterId');
  const rawOptions = form.get('options');
  const errors: Array<{ field: string; message: string }> = [];
  if (!isUploadedFile(file) || file.size === 0) errors.push({ field: 'file', message: 'Додайте файл.' });
  if (typeof kind !== 'string' || !KINDS.includes(kind as ImportKind)) {
    errors.push({ field: 'kind', message: 'Оберіть, що імпортуємо: розклад, програму, навчальний план чи розпорядження.' });
  }
  if (semesterId !== null && (typeof semesterId !== 'string' || !semesters.find(semesterId))) {
    errors.push({ field: 'semesterId', message: 'Семестр не знайдено.' });
  }
  let options: ImportOptions | null = null;
  if (typeof rawOptions === 'string' && rawOptions) {
    try {
      const parsed: unknown = JSON.parse(rawOptions);
      options = parseOptions(parsed, errors);
    } catch {
      errors.push({ field: 'options', message: 'Параметри мають бути JSON-об\'єктом.' });
    }
  }
  if (errors.length > 0) throw badRequest('Перевірте файл і параметри.', errors);
  const upload = file as File;
  if (upload.size > MAX_IMPORT_BYTES) {
    throw problem({ status: 413, slug: 'payload-too-large', title: 'Файл завеликий', detail: `Файл «${upload.name}» більший за 20 МБ.` });
  }
  const typedKind = kind as ImportKind;
  if (!KIND_EXTENSIONS[typedKind].includes(extensionOf(upload.name))) {
    throw problem({
      status: 415,
      slug: 'unsupported-media-type',
      title: 'Непідтримуваний формат файлу',
      detail: `${KIND_HINT[typedKind]} Файл «${upload.name}» має інший формат.`,
    });
  }
  // як CurriculumImportHandler.acceptUpload: для навчального плану семестр плану обовʼязковий одразу
  if (typedKind === 'CURRICULUM' && options?.semesterNumber === undefined) {
    throw badRequest('Перевірте файл і параметри.', [
      { field: 'options.semesterNumber', message: 'Вкажіть номер семестру навчального плану (1–8), який треба розпізнати.' },
    ]);
  }
  return { file: upload, kind: typedKind, semesterId: (semesterId as string | null) ?? null, options };
}

export const handlers = [
  http.get(apiPath('/import/jobs'), ({ request }) => {
    const url = new URL(request.url);
    const kind = url.searchParams.get('kind');
    const status = url.searchParams.get('status');
    const list = jobs
      .all()
      .map(advance)
      .filter((j) => (!kind || j.kind === kind) && (!status || j.status === status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || ukCollator.compare(a.fileName, b.fileName))
      .map((job) => toDto(job, true));
    // як importer: сторінка {items,page,size,total}, size 1..100
    return HttpResponse.json(paginate(list, url, 20, 100));
  }),
  http.get(apiPath('/import/jobs/:id'), ({ params }) => HttpResponse.json(toDto(advance(jobs.get(String(params.id)))))),
  http.post(apiPath('/import/jobs'), async ({ request }) => {
    const { file, kind, semesterId, options } = await readUpload(request);
    const now = new Date().toISOString();
    const job = jobs.insert({
      id: mockId('import-job', crypto.randomUUID()),
      kind,
      fileName: file.name,
      fileType: fileTypeOf(file.name),
      fileSize: file.size,
      status: 'UPLOADED',
      createdAt: now,
      updatedAt: now,
      createdBy: 'admin',
      summary: { recognized: 0, unknown: 0, conflicts: 0, warnings: 0 },
      report: [],
      draft: null,
      ai: null,
      error: null,
      semesterId,
      options,
      mappings: [],
      result: null,
      pendingPolls: 1,
      aiAt: null,
    });
    return HttpResponse.json(toDto(job), { status: 202 });
  }),
  http.put(apiPath('/import/jobs/:id/mappings'), async ({ request, params }) => {
    const job = jobs.get(String(params.id));
    const body = await readJson<{ mappings?: unknown }>(request);
    const next = applyMappings(job, body.mappings);
    return HttpResponse.json(toDto(jobs.update(job.id, () => next)));
  }),
  // як OrderDraftController: звірені рядки розпорядження замінюють чернетку, завдання готове до застосування
  http.put(apiPath('/import/jobs/:id/draft'), async ({ request, params }) => {
    const job = jobs.get(String(params.id));
    if (job.kind !== 'ORDER_DOCUMENT') throw businessRule('DRAFT_NOT_EDITABLE', 'Рядки можна редагувати лише в імпорті розпорядження.');
    const body = await readJson<Record<string, unknown>>(request);
    if (job.status !== 'NEEDS_REVIEW' && job.status !== 'READY' && !(job.status === 'FAILED' && job.draft != null)) {
      throw conflict(`Чернетку можна змінювати, лише коли завдання очікує перевірки; зараз — ${job.status}.`);
    }
    const { version: _version, ...draft } = body;
    void _version;
    // як OrderImportHandler.checkHeader: без номера, дати чи номера батьківського розпорядження — на перевірку
    const headerComplete = draft.number != null && draft.date != null && (draft.kind !== 'AMENDMENT' || draft.parentNumber != null);
    const next: StoredJob = {
      ...job,
      draft: { ...(job.draft as object), ...draft, reviewed: true },
      status: headerComplete ? 'READY' : 'NEEDS_REVIEW',
    };
    return HttpResponse.json(toDto(jobs.update(job.id, () => next)));
  }),
  // як ImportJobService.updateOptions: параметри застосування міняються без повторного завантаження
  http.put(apiPath('/import/jobs/:id/options'), async ({ request, params }) => {
    const job = jobs.get(String(params.id));
    if (job.status !== 'NEEDS_REVIEW' && job.status !== 'READY' && !(job.status === 'FAILED' && job.draft != null)) {
      throw conflict(`Параметри можна змінювати, лише коли завдання очікує перевірки; зараз — ${job.status}.`);
    }
    const body = await readJson<{ version?: unknown; options?: unknown }>(request);
    const errors: Array<{ field: string; message: string }> = [];
    if (typeof body.version !== 'number') errors.push({ field: 'version', message: 'Оновіть сторінку: бракує версії завдання' });
    const options = parseOptions(body.options ?? null, errors) ?? {};
    for (const key of ['sheet', 'semesterNumber'] as const) {
      if ((options[key] ?? null) !== (job.options?.[key] ?? null)) {
        errors.push({ field: `options.${key}`, message: 'Цей параметр визначає, як прочитано файл, — щоб його змінити, завантажте файл ще раз.' });
      }
    }
    if (errors.length > 0) throw badRequest('Перевірте параметри.', errors);
    const base = job.kind === 'DISCIPLINE_PROGRAM' ? programReport(groupIdsOf(options), job.semesterId) : job.report;
    const report = withReadiness(job.kind, base, options);
    const next: StoredJob = {
      ...job,
      options,
      report,
      summary: summaryOf(report),
      status: statusAfterParse(report),
      error: statusAfterParse(report) === 'READY' ? null : job.error,
      updatedAt: new Date().toISOString(),
    };
    return HttpResponse.json(toDto(jobs.update(job.id, () => next)));
  }),
  // як ImportJobService.requestApply: ідемпотентно для APPLIED/APPLYING, 409 під час розбору, 422 з кодами правил
  http.post(apiPath('/import/jobs/:id/apply'), async ({ params, request }) => {
    const job = jobs.get(String(params.id));
    const text = await request.text();
    const body = text ? (JSON.parse(text) as { semesterId?: Uuid | null }) : {};
    if (job.status === 'APPLIED' || job.status === 'APPLYING') return HttpResponse.json(toDto(job), { status: 202 });
    if (job.status === 'UPLOADED' || job.status === 'PARSING') throw conflict('Файл ще обробляється — зачекайте завершення розбору.');
    if (job.status === 'NEEDS_REVIEW' && !canApply(job)) {
      const decisions = job.report.some((r) => r.severity === 'WARN' && !r.matched && r.suggestions.length > 0);
      const firstError = job.report.find((r) => r.severity === 'ERROR');
      throw businessRule(
        'IMPORT_NEEDS_REVIEW',
        decisions || !firstError
          ? "Спершу розв'яжіть позначені розбіжності: оберіть відповідники або підтвердіть створення нових записів."
          : `Імпорт потребує перевірки: ${firstError.message}`,
      );
    }
    if (!canApply(job)) throw businessRule('IMPORT_FAILED', 'Файл не вдалося розібрати — завантажте його ще раз.');
    const semesterId = body.semesterId ?? job.semesterId;
    if (job.kind !== 'ORDER_DOCUMENT' && !semesterId) throw businessRule('SEMESTER_REQUIRED', 'Оберіть семестр, у який застосувати імпорт.');
    if ((job.kind === 'DISCIPLINE_PROGRAM' || job.kind === 'CURRICULUM') && !job.options?.groupCodes?.length) {
      throw businessRule('GROUPS_REQUIRED', 'Під час завантаження вкажіть групи, яким додати навантаження.');
    }
    const next = jobs.update(job.id, (current) => ({
      ...current,
      semesterId: semesterId ?? null,
      status: 'APPLYING',
      pendingPolls: 2,
      error: null,
      updatedAt: new Date().toISOString(),
    }));
    return HttpResponse.json(toDto(next), { status: 202 });
  }),
  http.get(apiPath('/import/ai/usage'), () => HttpResponse.json(aiUsageToday())),
];
