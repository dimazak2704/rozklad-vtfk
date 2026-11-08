/** Підписи Telegram-розділу: типи привʼязок і стани доставок. */
import type { BindingType, DeliveryDto, DeliveryPurpose, DeliveryStatus } from '@/api/types';
import type { BadgeTone } from '@/components/ui';

export const BINDING_TYPE_LABEL: Record<BindingType, string> = {
  GROUP_CHAT: 'Чат групи',
  TEACHER: 'Викладач',
  ADMIN_CHAT: 'Диспетчерська',
  SUBSCRIBER: 'Підписник',
};

export const DELIVERY_STATUS: Record<DeliveryStatus, { label: string; tone: BadgeTone }> = {
  SENT: { label: 'Надіслано', tone: 'success' },
  QUEUED: { label: 'У черзі', tone: 'neutral' },
  RETRYING: { label: 'Повтор', tone: 'warning' },
  FAILED: { label: 'Не доставлено', tone: 'danger' },
};

/** Що саме надсилали — за полем `purpose` журналу доставки. */
export const DELIVERY_PURPOSE_LABEL: Record<DeliveryPurpose, string> = {
  ORDER_GROUP: 'Розпорядження в чат групи',
  ORDER_TEACHER: 'Розпорядження викладачу',
  ORDER_ADMIN: 'Розпорядження диспетчерам',
  SUBSTITUTION_REQUEST: 'Запит на підтвердження заміни',
  SUBSTITUTION_NOTICE: 'Повідомлення про заміну',
  SUBSTITUTION_REMINDER: 'Нагадування про заміну',
  SUBSTITUTION_STATUS: 'Зміна стану заміни',
  SUBSTITUTION_ADMIN: 'Заміна — диспетчерам',
  ABSENCE_ADMIN: 'Нова відсутність',
  GENERATION_ADMIN: 'Генерація розкладу',
  DIGEST: 'Щоденний дайджест',
  TEST: 'Тестове повідомлення',
};

/** Підпис рядка журналу: для розпоряджень додаємо номер, якщо він відомий. */
export function deliveryKind(delivery: Pick<DeliveryDto, 'purpose' | 'orderId'>, orderNumber: number | null): string {
  const label = DELIVERY_PURPOSE_LABEL[delivery.purpose];
  return delivery.orderId && orderNumber !== null ? `${label} №${orderNumber}` : label;
}

/** Назва чату в журналі; без назви — маскований id (тестове повідомлення власнику не має привʼязки). */
export function deliveryChat(delivery: Pick<DeliveryDto, 'chatTitle' | 'chatId'>): string {
  return delivery.chatTitle ?? `Чат ${delivery.chatId}`;
}
