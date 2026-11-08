/** Підписи Telegram-розділу: типи привʼязок і стани доставок. */
import type { BindingType, DeliveryDto, DeliveryStatus } from '@/api/types';
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

/** Що саме надсилали: контракт не має поля «вид», тож визначаємо за привʼязкою й розпорядженням. */
export function deliveryKind(delivery: Pick<DeliveryDto, 'orderId' | 'bindingType'>, orderNumber: number | null): string {
  if (delivery.orderId) return orderNumber !== null ? `Розпорядження №${orderNumber}` : 'Розпорядження';
  if (delivery.bindingType === 'TEACHER') return 'Запит на підтвердження заміни';
  return 'Службове повідомлення';
}
