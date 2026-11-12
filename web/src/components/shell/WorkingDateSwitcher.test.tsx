/**
 * Парність робочої дати у верхній смузі диспетчера: з тижнів семестру (ручне перевизначення
 * виграє, DOMAIN §2), як у шапці розпорядження з сервера; арифметика чергування — лише поки тижні
 * не завантажилися чи недоступні.
 */
import { screen, waitFor } from '@testing-library/react';
import { http } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { CalendarWeek } from '@/api/calendar';
import type { PublicMetaDto } from '@/api/types';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { workingParity, WorkingDateSwitcher } from './WorkingDateSwitcher';

const meta = { today: '2026-11-12', parity: 'NUMERATOR', weekNumber: 11 } as Pick<PublicMetaDto, 'today' | 'parity' | 'weekNumber'>;
const semester = { startsOn: '2026-09-01', endsOn: '2026-12-30' };
const week = (weekStart: string, weekNumber: number, parity: CalendarWeek['parity'], overridden = false) =>
  ({ weekStart, weekNumber, parity, overridden }) as CalendarWeek;

describe('workingParity', () => {
  it('тиждень семестру з ручною парністю виграє в чергування', () => {
    const weeks = [week('2026-11-09', 11, 'NUMERATOR'), week('2026-11-16', 12, 'NUMERATOR', true)];
    expect(workingParity('2026-11-18', meta, semester, weeks)).toEqual({ parity: 'NUMERATOR', week: 12 });
  });

  it('поки тижні не завантажилися — чергування від сьогоднішнього тижня, не далі двох тижнів', () => {
    expect(workingParity('2026-11-18', meta, semester, undefined)).toEqual({ parity: 'DENOMINATOR', week: 12 });
    expect(workingParity('2026-12-07', meta, semester, undefined)).toBeNull();
  });

  it('дата поза семестром — парності немає, навіть якщо до неї тиждень', () => {
    const weeks = [week('2026-12-28', 18, 'DENOMINATOR')];
    expect(workingParity('2027-01-04', { ...meta, today: '2026-12-29', weekNumber: 18, parity: 'DENOMINATOR' }, semester, weeks)).toBeNull();
  });

  it('сьогодні поза семестром і тижнів немає — нічого не вигадуємо', () => {
    expect(workingParity('2027-01-20', { today: '2027-01-20', parity: null, weekNumber: null }, null, undefined)).toBeNull();
  });
});

describe('WorkingDateSwitcher', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-12T08:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('наступний тиждень перевизначено на чисельник — смуга показує чисельник, як шапка розпорядження', async () => {
    const { semester: active } = await api.get<PublicMetaDto>('/public/meta');
    if (!active) throw new Error('у моку немає активного семестру');
    // за чергуванням 16.11 — знаменник (12-й тиждень), але диспетчер поставив чисельник
    await api.put(`/admin/semesters/${active.id}/weeks/2026-11-16`, { parity: 'NUMERATOR' });

    renderWithProviders(<WorkingDateSwitcher />, { route: '/admin/changes?date=2026-11-18' });
    await waitFor(() => expect(screen.getAllByText('Чисельник').length).toBeGreaterThan(0));
    expect(screen.queryByText('Знаменник')).toBeNull();
  });

  it('тижні недоступні (збій) — лишається чергування від сьогодні', async () => {
    server.use(http.get(apiPath('/admin/semesters/:id/weeks'), () => problem({ status: 503 })));
    renderWithProviders(<WorkingDateSwitcher />, { route: '/admin/changes?date=2026-11-18' });
    await waitFor(() => expect(screen.getAllByText('Знаменник').length).toBeGreaterThan(0));
  });
});
