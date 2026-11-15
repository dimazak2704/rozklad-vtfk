/** Підписи листів на пошту: види листів одним простим реченням, стани журналу, стан адреси. */
import type { EmailDeliveryDto, EmailDeliveryStatus, EmailKind, EmailPreferencesDto } from '@/api/types';
import type { BadgeTone } from '@/components/ui';

export interface EmailKindText {
  label: string;
  /** Одне речення: коли приходить цей лист. */
  description: string;
}

export const EMAIL_KIND_TEXT: Record<EmailKind, EmailKindText> = {
  SUBSTITUTION_NOTICE: {
    label: 'Нова заміна',
    description: 'Коли вам ставлять заміну: дата, пара, група, аудиторія й посилання в кабінет.',
  },
  SUBSTITUTION_REQUEST: {
    label: 'Запит на підтвердження',
    description: 'Коли диспетчер просить підтвердити заміну до певного часу.',
  },
  SUBSTITUTION_CHANGED: {
    label: 'Заміну змінено',
    description: 'Коли в заміні, про яку вам уже писали, змінилися аудиторія, пара чи група.',
  },
  SUBSTITUTION_CANCELLED: {
    label: 'Заміну скасовано',
    description: 'Коли заміну, про яку вам писали, скасували або передали іншому викладачу.',
  },
  SUBSTITUTION_REMINDER: {
    label: 'Нагадування про відповідь',
    description: 'Коли ви не відповіли на запит до вказаного часу.',
  },
  ORDER_TEACHER: {
    label: 'Ваші рядки з розпорядження',
    description: 'Після кожного опублікованого розпорядження, де є ваші зміни; зручно, якщо ви не користуєтеся Telegram.',
  },
  SECURITY_TELEGRAM_RELINKED: {
    label: 'Telegram підключено в іншому чаті',
    description: 'Якщо ваш Telegram для розкладу підключили в іншому чаті. Цей лист приходить завжди.',
  },
  GENERATION_FINISHED: {
    label: 'Генерацію розкладу завершено',
    description: 'Коли генерація закінчилася, не вдалася чи її зупинили: з оцінкою й посиланням на результат.',
  },
  SUBSTITUTION_DECLINED: {
    label: 'Відмова від заміни',
    description: 'Коли викладач відмовився від заміни і треба обрати інший варіант.',
  },
  SUBSTITUTION_OBJECTION: {
    label: 'Доповнення до розпорядження',
    description: 'Коли викладач не може провести заміну, яка вже є в опублікованому розпорядженні.',
  },
  REQUEST_OVERDUE: {
    label: 'Немає відповіді на запит',
    description: 'Коли викладач не відповів на запит заміни до вказаного часу.',
  },
  ABSENCE_REPORTED: {
    label: 'Нова відсутність',
    description: 'Про кожну відсутність, яку повідомив викладач чи диспетчер (причини в листі немає).',
  },
  TEACHER_UNREACHABLE: {
    label: 'Викладач без Telegram',
    description: 'Коли викладачу із заміною бот не може написати і його треба повідомити самому.',
  },
};

/** Вид, якого SPA ще не знає (сервер додав новий): текст сервера або загальний підпис, але не ховаємо. */
export function emailKindText(kind: string, server?: { label?: string | null; description?: string | null }): EmailKindText {
  return (
    (EMAIL_KIND_TEXT as Record<string, EmailKindText | undefined>)[kind] ?? {
      label: server?.label ?? 'Інші листи',
      description: server?.description ?? 'Інший вид сповіщень системи.',
    }
  );
}

/** Підпис виду в журналі листів. */
export function emailLogKind(kind: EmailDeliveryDto['kind']): string {
  return kind === 'TEST' ? 'Тестовий лист' : emailKindText(kind).label;
}

export const EMAIL_STATUS: Record<EmailDeliveryStatus, { label: string; tone: BadgeTone }> = {
  SENT: { label: 'Надіслано', tone: 'success' },
  QUEUED: { label: 'У черзі', tone: 'neutral' },
  RETRYING: { label: 'Повтор', tone: 'warning' },
  FAILED: { label: 'Не надіслано', tone: 'danger' },
  SUPERSEDED: { label: 'Замінено новішим', tone: 'neutral' },
};

export function emailStatus(status: string): { label: string; tone: BadgeTone } {
  return (EMAIL_STATUS as Record<string, { label: string; tone: BadgeTone } | undefined>)[status] ?? { label: status, tone: 'neutral' };
}

/** Що людина бачить про свою адресу: одним рядком стан і, якщо листи не підуть, що зробити. */
export interface AddressNote {
  /** Листи на цю адресу підуть. */
  ok: boolean;
  text: string;
}

export function addressNote(prefs: Pick<EmailPreferencesDto, 'addressStatus' | 'verifiedBy'>): AddressNote {
  switch (prefs.addressStatus) {
    case 'VERIFIED':
      return {
        ok: true,
        text: prefs.verifiedBy === 'GOOGLE' ? 'Адресу підтверджено входом через Google.' : 'Корпоративна адреса коледжу.',
      };
    case 'UNVERIFIED':
      return {
        ok: false,
        text: 'Листи на цю адресу не надсилаються: її ще не підтверджено. Увійдіть один раз через Google з цією адресою — і листи почнуть приходити.',
      };
    case 'MISSING':
      return {
        ok: false,
        text: 'На вашому обліковому записі немає e-mail, тож листам нікуди йти. Попросіть навчальну частину додати вашу корпоративну адресу.',
      };
  }
}
