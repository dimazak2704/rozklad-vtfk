/**
 * Стан області «щоденна робота диспетчера» в мок-API: відсутності, зміни (заміни) і розпорядження.
 * Записи — DTO контракту плюс внутрішні поля, яких клієнт не отримує (звʼязок зі заняттям, автор).
 */
import type {
  AbsenceDto,
  ChangeDto,
  ChangeStatus,
  IsoDate,
  IsoInstant,
  OrderDto,
  Uuid,
} from '@/api/types';

/** Зміна з внутрішніми полями: яке заняття вона закриває і з якою парою міняє (SWAP). */
export interface ChangeRecord extends ChangeDto {
  createdAt: IsoInstant;
  originalLessonId: Uuid | null;
  swapLessonId: Uuid | null;
}

/** Розпорядження з полями, що є в списку (OrderSummaryDto), але не в OrderDto. */
export interface OrderRecord extends OrderDto {
  semesterId: Uuid;
  createdAt: IsoInstant;
  publishedAt: IsoInstant | null;
  publishedBy: string | null;
}

export interface World {
  absences: Map<Uuid, AbsenceDto>;
  changes: Map<Uuid, ChangeRecord>;
  orders: Map<Uuid, OrderRecord>;
}

export function emptyWorld(): World {
  return { absences: new Map(), changes: new Map(), orders: new Map() };
}

/** Зміни, що «тримають» пару: чернетка, очікування, підтверджена, внесена в розпорядження. */
export const LIVE_STATUSES: ReadonlySet<ChangeStatus> = new Set<ChangeStatus>([
  'DRAFT',
  'PENDING_CONFIRMATION',
  'CONFIRMED',
  'APPLIED',
]);

/** Хто діє від імені диспетчера в мок-API (демо-обліковий запис Keycloak, ARCHITECTURE §6). */
export const ADMIN_ACTOR = 'admin';

export function toChangeDto(record: ChangeRecord): ChangeDto {
  const { createdAt: _createdAt, originalLessonId: _original, swapLessonId: _swap, ...dto } = record;
  return dto;
}

export function toOrderDto(record: OrderRecord): OrderDto {
  const { semesterId: _semester, createdAt: _created, publishedAt: _published, publishedBy: _by, ...dto } = record;
  return dto;
}

export function changesOn(world: World, date: IsoDate): ChangeRecord[] {
  return [...world.changes.values()].filter((change) => change.date === date);
}
