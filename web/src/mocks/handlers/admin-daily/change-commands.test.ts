import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { ChangeDto } from '@/api/types';
import { resetMockStores } from '@/mocks/store';

/** Понеділок 09.11.2026, 07:40 за Києвом — демо-ранок; вечір того ж дня — усі пари вже минули. */
const MORNING = new Date('2026-11-09T05:40:00Z');
const EVENING = new Date('2026-11-09T20:00:00Z');
const DAY = '2026-11-09';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(MORNING);
  resetMockStores();
});
afterEach(() => vi.useRealTimers());

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Очікували помилку API');
}

describe('прохання підтвердити — як у core', () => {
  it('нагадати чи спитати ще раз про пару, що вже минула, — 422 CHANGE_LESSON_OVER, стан не змінюється', async () => {
    const pending = (await api.get<ChangeDto[]>('/admin/changes', { date: DAY, status: 'PENDING_CONFIRMATION' }))[0];
    const declined = (await api.get<ChangeDto[]>('/admin/changes', { date: DAY, status: 'DECLINED' }))[0];
    if (!pending || !declined) throw new Error('У демо-ранку мають бути очікування й відмова');

    vi.setSystemTime(EVENING);
    for (const change of [pending, declined]) {
      const refused = await failure(api.post(`/admin/changes/${change.id}/request-confirmation`));
      expect(refused).toMatchObject({ status: 422, code: 'CHANGE_LESSON_OVER' });
    }
    const after = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    expect(after.find((c) => c.id === declined.id)?.status).toBe('DECLINED');
    // підтвердити за викладача після пари можна
    const confirmed = await api.post<ChangeDto>(`/admin/changes/${pending.id}/confirm`);
    expect(confirmed.status).toBe('CONFIRMED');
  });

  it('спитати ще раз до пари: новий строк — не пізніше за початок першої пари', async () => {
    const declined = (await api.get<ChangeDto[]>('/admin/changes', { date: DAY, status: 'DECLINED' }))[0];
    if (!declined) throw new Error('У демо-ранку має бути відмова');
    const asked = await api.post<ChangeDto>(`/admin/changes/${declined.id}/request-confirmation`);
    const deadline = Date.parse(asked.confirmDeadline ?? '');
    expect(asked.status).toBe('PENDING_CONFIRMATION');
    expect(deadline).toBeGreaterThan(MORNING.getTime());
    expect(deadline).toBeLessThanOrEqual(MORNING.getTime() + 120 * 60_000);
  });
});
