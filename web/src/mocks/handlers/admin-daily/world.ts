/**
 * Стан області «щоденна робота диспетчера» в мок-API: відсутності, зміни (заміни) і розпорядження.
 * Зміни зберігаються рівно як ChangeDto core; розпорядження — OrderDto плюс внутрішні поля списку.
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

/** Зміна — рівно ChangeDto core (усі адитивні поля, зокрема `version`, уже в DTO). */
export type ChangeRecord = ChangeDto;

/** Розпорядження з внутрішніми полями (семестр для нумерації, час створення). */
export interface OrderRecord extends OrderDto {
  semesterId: Uuid;
  createdAt: IsoInstant;
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
  return { ...record };
}

export function toOrderDto(record: OrderRecord): OrderDto {
  const { semesterId: _semester, createdAt: _created, ...dto } = record;
  return dto;
}

export function changesOn(world: World, date: IsoDate): ChangeRecord[] {
  return [...world.changes.values()].filter((change) => change.date === date);
}
