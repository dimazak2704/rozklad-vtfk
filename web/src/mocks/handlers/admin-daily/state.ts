/**
 * Сховище області: один знімок стану (відсутності, зміни, розпорядження), щоб правила бачили його цілим.
 * Скидається разом з іншими сховищами (resetMockStores) — тоді демо-ранок будується заново від «зараз».
 */
import type { Uuid } from '@/api/types';
import { notFound } from '../../kit';
import { createMockStore } from '../../store';
import { seedWorld } from './seed';
import type { ChangeRecord, OrderRecord, World } from './world';

const store = createMockStore<World>(() => seedWorld(new Date()));

export function world(): World {
  return store.get();
}

export function getChange(id: Uuid): ChangeRecord {
  const change = world().changes.get(id);
  if (!change) throw notFound(`Зміну з id ${id} не знайдено.`);
  return change;
}

export function saveChange(change: ChangeRecord): ChangeRecord {
  world().changes.set(change.id, change);
  return change;
}

export function getOrder(id: Uuid): OrderRecord {
  const order = world().orders.get(id);
  if (!order) throw notFound(`Розпорядження з id ${id} не знайдено.`);
  return order;
}
