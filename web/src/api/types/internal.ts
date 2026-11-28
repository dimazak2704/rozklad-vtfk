import type { Nullable, ReplyDecision, RoomType, Uuid } from './common';
import type { PeriodKindDto, SemesterDto } from './calendar';
import type { AbsenceRequest } from './changes';

// ───────────────────────── 4. Internal (SERVICE) — SPA не викликає, типи для повноти ─────────────────────────

export interface InternalChangeReplyRequest {
  teacherId: Uuid;
  decision: ReplyDecision;
  comment?: Nullable<string>;
}

export interface InternalAbsenceRequest extends AbsenceRequest {
  source: 'BOT';
}

export interface CatalogSnapshotDto {
  groups: Array<{ id: Uuid; code: string; course: number; subgroups: number[] }>;
  teachers: Array<{ id: Uuid; shortName: string; displayName: string; lastName: string; aliases: string[] }>;
  disciplines: Array<{ id: Uuid; shortName: string; name: string; aliases: string[] }>;
  rooms: Array<{ id: Uuid; name: string; type: RoomType }>;
  semesters: SemesterDto[];
  /** Усі види періодів, і вимкнені теж (адитивно; старіший core поля не має). */
  periodKinds?: Array<Pick<PeriodKindDto, 'id' | 'code' | 'name' | 'effect' | 'color' | 'aliases' | 'noteTemplate' | 'active'>>;
}
