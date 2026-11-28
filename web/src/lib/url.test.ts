import { afterEach, describe, expect, it, vi } from 'vitest';
import { sessionStorageOrNull } from './storage';
import { safeDecode } from './url';

afterEach(() => vi.restoreAllMocks());

describe('safeDecode', () => {
  it('декодує адресу, а зламану повертає як є', () => {
    expect(safeDecode('4%D0%9A%D0%861')).toBe('4КІ1');
    expect(safeDecode('%E0%A4%A')).toBe('%E0%A4%A');
  });
});

describe('sessionStorageOrNull', () => {
  it('сховище є — віддає його; браузер забороняє доступ — null', () => {
    expect(sessionStorageOrNull()).toBe(window.sessionStorage);
    vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(sessionStorageOrNull()).toBeNull();
  });
});
