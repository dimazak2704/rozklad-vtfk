import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import type { BotStatusDto, DashboardDto, GenerationJobStatus } from '@/api/types';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useServiceAlerts } from './useServiceAlerts';

const bot = (patch: Partial<BotStatusDto> = {}): BotStatusDto => ({
  username: 'scheduler_vtfk_bot',
  link: 'https://t.me/scheduler_vtfk_bot',
  mode: 'POLLING',
  connected: true,
  ...patch,
});

const dashboard = (status: GenerationJobStatus | null) => ({ generation: status ? { status } : null }) as unknown as DashboardDto;

function render(value: DashboardDto | undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const hook = renderHook(() => useServiceAlerts(value), { wrapper });
  return { hook, client };
}

describe('useServiceAlerts: сервіси на панелі «Сьогодні» лише коли зламані', () => {
  it('справний бот і генератор без збою — жодного рядка', async () => {
    server.use(http.get(apiPath('/notify/bot'), () => Response.json(bot())));
    const { hook, client } = render(dashboard('SOLVED'));
    await waitFor(() => expect(client.isFetching()).toBe(0));
    expect(hook.result.current).toEqual([]);
  });

  it('бот не підключений і збій генерації — кожне своїм реченням із переходом у розділ', async () => {
    server.use(http.get(apiPath('/notify/bot'), () => Response.json(bot({ connected: false }))));
    const { hook } = render(dashboard('FAILED'));
    await waitFor(() =>
      expect(hook.result.current.map(({ message, link }) => [message, link])).toEqual([
        ['Telegram-бот не відповідає — викладачі не отримають прохань', '/admin/telegram'],
        ['Остання генерація розкладу не вдалася', '/admin/generation'],
      ]),
    );
  });

  it('сервіс сповіщень недоступний — каже, що стан бота перевірити не вдалося', async () => {
    server.use(http.get(apiPath('/notify/bot'), () => problem({ status: 503, detail: 'Сервіс тимчасово недоступний.' })));
    const { hook } = render(undefined);
    await waitFor(() => expect(hook.result.current.map((alert) => alert.message)).toEqual(['Не вдалося перевірити Telegram-бота']));
  });
});
