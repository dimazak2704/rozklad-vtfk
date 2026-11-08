import { describe, expect, it } from 'vitest';
import { REJECTION_WINDOW_MS, clearRejectedToken, loginAfterRejection, noteRejectedToken } from './rejectedToken';

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key) => data.get(key) ?? null,
    key: (index) => [...data.keys()][index] ?? null,
    removeItem: (key) => void data.delete(key),
    setItem: (key, value) => void data.set(key, value),
  };
}

describe('rejectedToken', () => {
  it('перша відмова — забути токен і увійти ще раз; друга за хвилину — зупинитися', () => {
    const storage = memoryStorage();
    expect(noteRejectedToken(1_000, storage)).toBe(false);
    expect(noteRejectedToken(1_000 + REJECTION_WINDOW_MS - 1, storage)).toBe(true);
  });

  it('давня відмова або скинутий лічильник не зупиняють вхід', () => {
    const storage = memoryStorage();
    noteRejectedToken(1_000, storage);
    expect(noteRejectedToken(1_000 + REJECTION_WINDOW_MS + 1, storage)).toBe(false);
    clearRejectedToken(storage);
    expect(noteRejectedToken(1_000 + REJECTION_WINDOW_MS + 2, storage)).toBe(false);
  });

  it('адреса входу після повторної відмови несе rejected=1', () => {
    expect(loginAfterRejection('/login?returnTo=%2Fadmin', false)).toBe('/login?returnTo=%2Fadmin');
    expect(loginAfterRejection('/login?returnTo=%2Fadmin', true)).toBe('/login?returnTo=%2Fadmin&rejected=1');
    expect(loginAfterRejection('/login', true)).toBe('/login?rejected=1');
  });
});
