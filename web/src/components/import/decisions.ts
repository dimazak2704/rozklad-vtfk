/**
 * Рішення щодо назв імпорту (CONTRACTS §5 `mappings[]`): які рядки звіту потребують рішення і як
 * рішення інтерфейсу перетворюється на тіло PUT …/mappings.
 */
import { isEntityRow, needsDecision, type ImportJob, type ImportReportRow } from '@/api/import';
import type { ImportMapping, MappingType } from '@/api/types';

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

/** Початковий стан: збережене раніше рішення, «створити» для нового без кандидатів, інакше — не вирішено. */
export function initialDecision(row: EntityReportRow, mapping: ImportMapping | undefined): Decision {
  if (mapping?.targetId) return { action: mapping.addAlias ? 'alias' : 'existing', targetId: mapping.targetId };
  if (mapping?.createNew) return { action: 'create', targetId: null };
  if (needsDecision(row)) return { action: null, targetId: null };
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
