/**
 * Рішення щодо назв імпорту (CONTRACTS §5 `mappings[]`): які рядки звіту потребують рішення і як
 * рішення інтерфейсу перетворюється на тіло PUT …/mappings.
 */
import { isEntityRow, needsDecision, type ImportJob, type ImportReportRow } from '@/api/import';
import type { ImportKind, ImportMapping, MappingType } from '@/api/types';

/** existing — зіставити; alias — зіставити й додати написання псевдонімом; create — новий запис. */
export type MappingAction = 'existing' | 'alias' | 'create';

export interface Decision {
  action: MappingAction | null;
  targetId: string | null;
}

export type EntityReportRow = ImportReportRow & { type: MappingType };

export const decisionKey = (row: Pick<ImportReportRow, 'type' | 'raw'>): string => `${row.type}|${row.raw}`;

/** Рядки, де рішення потрібне чи можливе: можливі дублікати, нові записи, уже вирішені раніше. */
export function decisionRows(job: Pick<ImportJob, 'report' | 'mappings'>): EntityReportRow[] {
  const decided = new Set((job.mappings ?? []).map(decisionKey));
  return job.report.filter(isEntityRow).filter((row) => needsDecision(row) || !row.matched || decided.has(decisionKey(row)));
}

/**
 * Чи застосування цього виду створює новий запис каталогу (як ImportHandler.creates importer):
 * розпорядження зберігається як текст і не створює нічого; програма й навчальний план додають
 * навантаження наявним групам і можуть створити лише дисципліну; розклад створює все.
 */
export function creates(kind: ImportKind, type: MappingType): boolean {
  if (kind === 'ORDER_DOCUMENT') return false;
  if (kind === 'DISCIPLINE_PROGRAM' || kind === 'CURRICULUM') return type === 'DISCIPLINE';
  return true;
}

/**
 * Початковий стан: збережене раніше рішення, «створити» для нового без кандидатів (якщо цей імпорт
 * взагалі створює такі записи), інакше — не вирішено. Рядок-помилка (новий запис, створення якого
 * треба свідомо підтвердити, як дисципліна навчального плану) теж лишається не вирішеним.
 */
export function initialDecision(row: EntityReportRow, mapping: ImportMapping | undefined, creatable = true): Decision {
  if (mapping?.targetId) return { action: mapping.addAlias ? 'alias' : 'existing', targetId: mapping.targetId };
  if (mapping?.createNew && creatable) return { action: 'create', targetId: null };
  if (needsDecision(row) || row.severity === 'ERROR' || !creatable) return { action: null, targetId: null };
  return { action: 'create', targetId: null };
}

/** Чи рішення повне: дія вибрана, а для зіставлення — ще й запис каталогу. */
export function isComplete(decision: Decision | undefined): boolean {
  if (!decision?.action) return false;
  return decision.action === 'create' || Boolean(decision.targetId);
}

/** Рішення → елемент `mappings[]`; null для неповного рішення. */
export function toMapping(row: EntityReportRow, decision: Decision): ImportMapping | null {
  if (decision.action === 'create') return { type: row.type, raw: row.raw, targetId: null, createNew: {}, addAlias: false };
  if ((decision.action === 'existing' || decision.action === 'alias') && decision.targetId) {
    return { type: row.type, raw: row.raw, targetId: decision.targetId, createNew: null, addAlias: decision.action === 'alias' };
  }
  return null;
}

/** Вкладка перегляду, де виправляють те, що блокує застосування. */
export type ReviewTab = 'decisions' | 'report' | 'draft';

/** Рядок звіту з параметрів імпорту (групи, години на тиждень) — їх виправляють на вкладці «Чернетка». */
export const OPTIONS_LOCATION = 'параметри імпорту';

/**
 * Що блокує застосування і де це виправити: рішення щодо назв — «Рішення»; заголовок і рядки
 * розпорядження, параметри програми чи плану — «Чернетка»; решта — «Звіт».
 */
export function blockingReason(job: Pick<ImportJob, 'kind' | 'report'>): { decisions: number; errors: ImportReportRow[]; tab: ReviewTab } {
  const decisions = job.report.filter(needsDecision).length;
  const errors = job.report.filter((row) => row.severity === 'ERROR');
  if (decisions > 0) return { decisions, errors, tab: 'decisions' };
  const first = errors[0];
  const tab: ReviewTab =
    !first
      ? 'report'
      : job.kind === 'ORDER_DOCUMENT' || first.location === OPTIONS_LOCATION
        ? 'draft'
        : isEntityRow(first)
          ? 'decisions'
          : 'report';
  return { decisions, errors, tab };
}
