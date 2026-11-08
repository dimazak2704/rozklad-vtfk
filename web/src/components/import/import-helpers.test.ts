import { describe, expect, it } from 'vitest';
import type { ImportReportRow } from '@/api/import';
import { needsDecision, typedDraft } from '@/api/import';
import { decisionRows, initialDecision, isComplete, toMapping, type EntityReportRow } from './decisions';
import { detectImportKind, fileProblem, formatBytes, kindAccepts, MAX_IMPORT_BYTES, usesVision } from './detect';

describe('визначення виду імпорту', () => {
  it.each([
    ['Розклад 2025-2026.xlsx', 'TIMETABLE_XLSX'],
    ['навчальний план КІ.xlsx', 'CURRICULUM'],
    ['Програма ЕУІТП.docx', 'DISCIPLINE_PROGRAM'],
    ['working-program-ndeksm.doc', 'DISCIPLINE_PROGRAM'],
    ['розпорядження №35.docx', 'ORDER_DOCUMENT'],
    ['order-035-2026-10-19.jpg', 'ORDER_DOCUMENT'],
    ['IMG_2041.PNG', 'ORDER_DOCUMENT'],
    ['план-скан.pdf', 'CURRICULUM'],
    ['програма КБ.pdf', 'DISCIPLINE_PROGRAM'],
  ])('%s → %s', (name, kind) => {
    expect(detectImportKind(name)?.kind).toBe(kind);
  });

  it('незнайомий формат не вгадує', () => {
    expect(detectImportKind('розклад.ods')).toBeNull();
    expect(detectImportKind('без-розширення')).toBeNull();
  });

  it('пояснює, що з файлом не так', () => {
    expect(fileProblem({ name: 'розклад.ods', size: 10 })).toMatch(/не підходить/);
    expect(fileProblem({ name: 'розклад.xlsx', size: 0 })).toBe('Файл «розклад.xlsx» порожній.');
    expect(fileProblem({ name: 'скан.pdf', size: MAX_IMPORT_BYTES + 1 })).toBe('Файл «скан.pdf» більший за 20 МБ.');
    expect(fileProblem({ name: 'скан.pdf', size: 1024 })).toBeNull();
  });

  it('формати за видом і витрати ШІ', () => {
    expect(kindAccepts('TIMETABLE_XLSX', 'Розклад.XLSX')).toBe(true);
    expect(kindAccepts('TIMETABLE_XLSX', 'розклад.pdf')).toBe(false);
    expect(usesVision('ORDER_DOCUMENT', 'фото.jpg')).toBe(true);
    expect(usesVision('ORDER_DOCUMENT', 'розпорядження.docx')).toBe(false);
    expect(usesVision('TIMETABLE_XLSX', 'розклад.xlsx')).toBe(false);
    expect(formatBytes(1_468_006)).toBe('1,4 МБ');
    expect(formatBytes(512)).toBe('1 КБ');
  });
});

const row = (patch: Partial<ImportReportRow> & Pick<ImportReportRow, 'type' | 'raw'>): ImportReportRow => ({
  severity: 'OK',
  suggestions: [],
  location: 'Розклад друк',
  ...patch,
});

describe('рішення щодо назв', () => {
  const typo = row({ type: 'TEACHER', raw: 'Граболінська', severity: 'WARN', suggestions: [{ id: 't1', label: 'Гарболінська', score: 0.94 }] });
  const fresh = row({ type: 'TEACHER', raw: 'Ємельяненко', severity: 'INFO' });
  const known = row({ type: 'GROUP', raw: 'ІКІ1', severity: 'WARN', matched: { id: 'g1', label: '1КІ1', score: 0.9 } });
  const cell = row({ type: 'LESSON', raw: 'Крисак Л./Можаровська', severity: 'INFO' });

  it('рішення потрібне для можливих дублікатів і нових записів, а не для знайдених чи службових рядків', () => {
    expect(needsDecision(typo)).toBe(true);
    expect(needsDecision(known)).toBe(false);
    expect(decisionRows({ report: [typo, fresh, known, cell], mappings: [] }).map((r) => r.raw)).toEqual(['Граболінська', 'Ємельяненко']);
  });

  it('уже вирішені рядки лишаються в списку, щоб рішення можна було змінити', () => {
    const decided = { ...typo, severity: 'OK' as const, matched: { id: 't1', label: 'Гарболінська', score: 1 } };
    const rows = decisionRows({ report: [decided], mappings: [{ type: 'TEACHER', raw: 'Граболінська', targetId: 't1', addAlias: true }] });
    expect(rows).toHaveLength(1);
    expect(initialDecision(rows[0] as EntityReportRow, { type: 'TEACHER', raw: 'Граболінська', targetId: 't1', addAlias: true })).toEqual({
      action: 'alias',
      targetId: 't1',
    });
  });

  it('початковий стан: дублікат — не вирішено, нове без кандидатів — створити', () => {
    expect(initialDecision(typo as EntityReportRow, undefined)).toEqual({ action: null, targetId: null });
    expect(initialDecision(fresh as EntityReportRow, undefined)).toEqual({ action: 'create', targetId: null });
  });

  it('рішення → mappings[] за контрактом', () => {
    const entity = typo as EntityReportRow;
    expect(toMapping(entity, { action: 'alias', targetId: 't1' })).toEqual({
      type: 'TEACHER',
      raw: 'Граболінська',
      targetId: 't1',
      createNew: null,
      addAlias: true,
    });
    expect(toMapping(entity, { action: 'create', targetId: null })).toEqual({
      type: 'TEACHER',
      raw: 'Граболінська',
      targetId: null,
      createNew: {},
      addAlias: false,
    });
    expect(toMapping(entity, { action: 'existing', targetId: null })).toBeNull();
    expect(isComplete({ action: 'existing', targetId: null })).toBe(false);
    expect(isComplete({ action: 'create', targetId: null })).toBe(true);
  });
});

describe('чернетка за видом імпорту', () => {
  it('перевіряє форму, а не вірить наосліп', () => {
    expect(typedDraft({ kind: 'ORDER_DOCUMENT', draft: { rows: [], notes: [], kind: 'ORDER', warnings: [] } })?.kind).toBe('ORDER_DOCUMENT');
    expect(typedDraft({ kind: 'TIMETABLE_XLSX', draft: { rows: [] } })).toBeNull();
    expect(typedDraft({ kind: 'CURRICULUM', draft: null })).toBeNull();
  });
});
