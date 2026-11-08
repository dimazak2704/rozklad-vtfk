import { screen } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import type { AiUsageDto, DashboardDto } from '@/api/types';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { SystemStatus } from './SystemStatus';

// core завжди віддає aiSpendToday: null — витрати рахує importer
const DASHBOARD = { aiSpendToday: null, generation: null } as unknown as DashboardDto;

const usage = (patch: Partial<AiUsageDto> = {}): AiUsageDto => ({
  today: { calls: 24, inputTokens: 120_000, outputTokens: 9_000, costUsd: 0.525665 },
  dailyBudgetUsd: 2,
  remainingUsd: 1.474335,
  model: { text: 'claude-haiku-5-5', vision: 'claude-sonnet-5-5' },
  enabled: true,
  ...patch,
});

const tile = async () => (await screen.findByRole('link', { name: 'Витрати ШІ: налаштування' })).textContent ?? '';

describe('SystemStatus: «ШІ сьогодні»', () => {
  it('показує витрати importer проти бюджету, а не «Немає даних» з core', async () => {
    server.use(http.get(apiPath('/import/ai/usage'), () => Response.json(usage())));
    renderWithProviders(<SystemStatus dashboard={DASHBOARD} />);
    expect(await screen.findByText(/^0,53\s\$ з 2,00\s\$$/)).toBeInTheDocument();
    expect(await tile()).not.toContain('Немає даних');
  });

  it('вичерпаний бюджет, вимкнений ШІ і недоступний importer — кожне своїми словами', async () => {
    server.use(http.get(apiPath('/import/ai/usage'), () => Response.json(usage({ today: { calls: 90, inputTokens: 1, outputTokens: 1, costUsd: 2.01 }, remainingUsd: 0 }))));
    const first = renderWithProviders(<SystemStatus dashboard={DASHBOARD} />);
    expect(await screen.findByText(/бюджет вичерпано/)).toBeInTheDocument();
    first.unmount();

    server.use(http.get(apiPath('/import/ai/usage'), () => Response.json(usage({ enabled: false }))));
    const second = renderWithProviders(<SystemStatus dashboard={DASHBOARD} />);
    expect(await screen.findByText('Вимкнено: не налаштовано ключ API')).toBeInTheDocument();
    second.unmount();

    server.use(http.get(apiPath('/import/ai/usage'), () => problem({ status: 503, detail: 'Сервіс тимчасово недоступний.' })));
    renderWithProviders(<SystemStatus dashboard={DASHBOARD} />);
    expect(await screen.findByText('Невідомо — сервіс імпорту не відповідає')).toBeInTheDocument();
  });
});
