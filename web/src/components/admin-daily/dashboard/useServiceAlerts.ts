import { useMemo } from 'react';
import { useBotStatus } from '@/api/telegram';
import type { DashboardAlert, DashboardDto } from '@/api/types';

/**
 * Сервіси довкола змін зʼявляються на «Огляді» лише тоді, коли щось зламано, — одним рядком у
 * «Що потребує рішення». Справний бот і генератор без задач — не новина; витрати ШІ живуть в «Імпорті».
 */
export function useServiceAlerts(dashboard: DashboardDto | undefined): DashboardAlert[] {
  const bot = useBotStatus();
  const generation = dashboard?.generation?.status ?? null;
  return useMemo(() => {
    const alerts: DashboardAlert[] = [];
    if (bot.isError && !bot.data) {
      alerts.push({
        kind: 'BOT_UNKNOWN',
        severity: 'WARN',
        message: 'Не вдалося перевірити Telegram-бота',
        link: '/admin/telegram',
      });
    } else if (bot.data && !bot.data.connected) {
      alerts.push({
        kind: 'BOT_OFFLINE',
        severity: 'ERROR',
        message: 'Telegram-бот не відповідає — викладачі не отримають прохань',
        link: '/admin/telegram',
      });
    }
    if (generation === 'FAILED') {
      alerts.push({ kind: 'GENERATION_FAILED', severity: 'WARN', message: 'Остання генерація розкладу не вдалася', link: '/admin/generation' });
    }
    return alerts;
  }, [bot.isError, bot.data, generation]);
}
