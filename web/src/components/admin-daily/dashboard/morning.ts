/** Модель «маршруту ранку» на панелі: етапи підготовки змін на дату і де диспетчер зараз. */
import type { DashboardDto, IsoDate, OrderSummaryDto } from '@/api/types';
import { formatInstantTime } from '@/lib/date';
import { countLabel } from '@/lib/labels';

export type StopState = 'done' | 'current' | 'ahead';

export interface RouteStop {
  id: 'absences' | 'decisions' | 'confirmations' | 'draft' | 'published';
  value: string;
  label: string;
  hint: string;
  href: string;
  state: StopState;
  /** Число означає незроблену роботу (червоне), а не просто лічильник. */
  alarm: boolean;
}

const DECLINES = ['відмова', 'відмови', 'відмов'] as const;

/**
 * Зупинки: відсутні → без рішення → чекають відповіді → чернетка → опубліковано.
 * Поточна — перша, де ще є робота; усі до неї пройдені.
 */
export function buildStops(dashboard: DashboardDto, published: OrderSummaryDto | null, date: IsoDate): RouteStop[] {
  const changes = `/admin/changes?date=${date}`;
  const orders = `/admin/orders?date=${date}`;
  const draftRows = dashboard.orderDraft?.itemCount ?? 0;
  const raw: Array<Omit<RouteStop, 'state'> & { done: boolean }> = [
    {
      id: 'absences',
      value: String(dashboard.absences),
      label: 'Відсутні',
      hint: dashboard.absences === 0 ? 'Усі викладачі на місці' : 'Їхні пари треба закрити',
      href: changes,
      done: true,
      alarm: false,
    },
    {
      id: 'decisions',
      value: String(dashboard.uncoveredLessons),
      label: 'Без рішення',
      hint: dashboard.uncoveredLessons === 0 ? 'Усі пари закрито' : 'Оберіть заміну',
      href: `${changes}&filter=uncovered`,
      done: dashboard.uncoveredLessons === 0,
      alarm: dashboard.uncoveredLessons > 0,
    },
    {
      id: 'confirmations',
      value: String(dashboard.pending),
      label: 'Чекають відповіді',
      hint:
        dashboard.declined > 0
          ? `і ${countLabel(dashboard.declined, DECLINES)}: потрібен інший`
          : dashboard.pending > 0
            ? 'Запити надіслано в Telegram'
            : 'Усі відповіли',
      href: `${changes}&filter=${dashboard.declined > 0 ? 'declined' : 'pending'}`,
      done: dashboard.pending === 0 && dashboard.declined === 0,
      alarm: dashboard.declined > 0,
    },
    {
      id: 'draft',
      value: String(draftRows),
      label: 'Рядків у чернетці',
      hint: draftRows > 0 ? 'Перегляньте й опублікуйте' : published ? 'Нових змін немає' : 'Підтверджених змін ще немає',
      href: orders,
      done: draftRows === 0 && published !== null,
      alarm: false,
    },
    {
      id: 'published',
      value: published?.number ? `№${published.number}` : '—',
      label: 'Опубліковано',
      hint: published?.publishedAt ? `о ${formatInstantTime(published.publishedAt)}, групи отримали в Telegram` : 'Ще не опубліковано',
      href: orders,
      done: published !== null && draftRows === 0,
      alarm: false,
    },
  ];
  // маршрут читається зліва направо: усе до першої незробленої зупинки пройдено, далі — попереду
  const current = raw.findIndex((stop) => !stop.done);
  return raw.map(({ done: _done, ...stop }, index) => {
    const state: StopState = current === -1 || index < current ? 'done' : index === current ? 'current' : 'ahead';
    return { ...stop, state };
  });
}

/** Скільки зупинок підряд пройдено від початку (для синьої частини лінії). */
export function reachedCount(stops: readonly RouteStop[]): number {
  const index = stops.findIndex((stop) => stop.state !== 'done');
  return index === -1 ? stops.length : index;
}
