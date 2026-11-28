import type { IsoDate, Uuid } from './common';
import type { GenerationJobDto } from './generation';
import type { AbsenceSummaryDto, ChangeDto } from './changes';
import type { OrderSummaryDto } from './orders';

// ───────────────────────── SSE: GET /api/events/stream ─────────────────────────

/** Подія `schedule.changed` (інвалідація кешу). */
export interface ScheduleChangedPayload {
  dates: IsoDate[];
  groupIds: Uuid[];
  teacherIds: Uuid[];
  reason: string;
}

/** Подія `generation.progress`. @inferred: контракт каже «JSON of the related DTO». */
export interface GenerationProgressPayload {
  jobId: Uuid;
  elapsedMs: number;
  hardScore: number;
  softScore: number;
}

export interface ServerEventMap {
  'change.updated': ChangeDto;
  'absence.created': AbsenceSummaryDto;
  'absence.updated': AbsenceSummaryDto;
  'generation.progress': GenerationProgressPayload;
  'generation.completed': GenerationJobDto;
  /** Короткий запис розпорядження (як рядок списку), а не повний OrderDto. */
  'order.published': OrderSummaryDto;
  'schedule.changed': ScheduleChangedPayload;
  ping: unknown;
}

export type ServerEventType = keyof ServerEventMap;

export type ServerEvent = {
  [K in ServerEventType]: { type: K; data: ServerEventMap[K]; id?: string };
}[ServerEventType];
