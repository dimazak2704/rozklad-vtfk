import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CLOCK_JUMP_MS, clockState, now, nowMs, resetClock, subscribeClock, syncClock, toClockMs } from './clock';
import { kyivClock, todayInKyiv } from './date';

const REAL = Date.parse('2026-11-14T12:00:00Z'); // субота, 14:00 у Києві
const STAND = '2026-11-20T08:00:00Z'; // пʼятниця, 10:00 у Києві — середина 2 пари

describe('годинник застосунку', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(REAL);
  });

  afterEach(() => {
    resetClock();
    vi.useRealTimers();
  });

  it('без переведеного часу — час браузера', () => {
    syncClock({ now: new Date(REAL).toISOString() }, REAL, REAL);
    expect(nowMs()).toBe(REAL);
    expect(clockState()).toMatchObject({ standTime: false, offsetMs: 0 });
  });

  it('на стенді бере зсув від серверного now і йде далі разом зі справжнім часом', () => {
    syncClock({ now: STAND, standTime: true }, REAL, REAL);
    expect(now().toISOString()).toBe('2026-11-20T08:00:00.000Z');
    expect(todayInKyiv()).toBe('2026-11-20');
    expect(kyivClock().minutes).toBe(10 * 60);

    vi.setSystemTime(REAL + 25 * 60_000);
    expect(kyivClock().minutes).toBe(10 * 60 + 25);
  });

  it('серверний момент відносить до середини запиту, а не до його кінця', () => {
    // запит ішов 2 с: сервер відповів посередині, о REAL + 1 с за справжнім часом
    syncClock({ now: STAND, standTime: true }, REAL, REAL + 2_000);
    vi.setSystemTime(REAL + 1_000);
    expect(nowMs()).toBe(Date.parse(STAND));
  });

  it('перетворює справжню мітку часу (оновлення даних) на час стенда', () => {
    syncClock({ now: STAND, standTime: true }, REAL, REAL);
    expect(new Date(toClockMs(REAL + 60_000)).toISOString()).toBe('2026-11-20T08:01:00.000Z');
  });

  it('стрибок часу міняє epoch, дрібна похибка мережі — ні', () => {
    const calls = vi.fn();
    const stop = subscribeClock(calls);
    syncClock({ now: STAND, standTime: true }, REAL, REAL);
    expect(clockState().epoch).toBe(1);

    syncClock({ now: new Date(Date.parse(STAND) + 800).toISOString(), standTime: true }, REAL, REAL);
    expect(clockState().epoch).toBe(1);
    expect(clockState().offsetMs).toBe(Date.parse(STAND) + 800 - REAL);

    syncClock({ now: new Date(Date.parse(STAND) + CLOCK_JUMP_MS + 1_000).toISOString(), standTime: true }, REAL, REAL);
    expect(clockState().epoch).toBe(2);

    // стенд повернули на справжній час
    syncClock({ now: new Date(REAL).toISOString() }, REAL, REAL);
    expect(clockState()).toMatchObject({ standTime: false, offsetMs: 0, epoch: 3 });
    expect(calls).toHaveBeenCalledTimes(4);
    stop();
  });

  it('незрозумілий серверний момент годинник не чіпає', () => {
    syncClock({ now: 'вчора', standTime: true }, REAL, REAL);
    syncClock({ standTime: true }, REAL, REAL);
    expect(clockState()).toMatchObject({ standTime: false, offsetMs: 0, epoch: 0 });
  });

  it('памʼятає зсув стенда між завантаженнями й забуває його у звичайному режимі', async () => {
    syncClock({ now: STAND, standTime: true }, REAL, REAL);
    expect(JSON.parse(localStorage.getItem('rozklad.stand-clock') ?? '{}')).toEqual({ offsetMs: Date.parse(STAND) - REAL });

    vi.resetModules();
    const reloaded = await import('./clock');
    expect(reloaded.clockState()).toMatchObject({ standTime: true, epoch: 0 });
    expect(reloaded.now().toISOString()).toBe('2026-11-20T08:00:00.000Z');

    syncClock({ now: new Date(REAL).toISOString() }, REAL, REAL);
    expect(localStorage.getItem('rozklad.stand-clock')).toBeNull();
  });
});
