import type { User } from 'oidc-client-ts';
import { describe, expect, it, vi } from 'vitest';
import { takeUnsavedChange, noteUnsavedChange } from './unsavedChange';
import { getAccessToken } from './userManager';

function user(token: string, expiresIn: number, refreshToken: string | undefined = 'refresh'): User {
  return { access_token: token, expires_in: expiresIn, expired: expiresIn <= 0, refresh_token: refreshToken } as unknown as User;
}

function manager(current: User | null, renewed: () => Promise<User | null>) {
  return { getUser: vi.fn(() => Promise.resolve(current)), signinSilent: vi.fn(renewed) };
}

describe('getAccessToken', () => {
  it('дійсний токен віддає без оновлення', async () => {
    const m = manager(user('fresh', 600), () => Promise.resolve(null));
    await expect(getAccessToken(m)).resolves.toBe('fresh');
    expect(m.signinSilent).not.toHaveBeenCalled();
  });

  it('токен, що закінчився (ноутбук спав), спершу оновлює refresh token-ом, а не шле запит без нього', async () => {
    const m = manager(user('old', -60), () => Promise.resolve(user('renewed', 900)));
    await expect(getAccessToken(m)).resolves.toBe('renewed');
    expect(m.signinSilent).toHaveBeenCalledOnce();
  });

  it('паралельні запити чекають одне оновлення', async () => {
    let resolve: (value: User) => void = () => undefined;
    const m = manager(user('old', -60), () => new Promise<User>((done) => (resolve = done)));
    const both = Promise.all([getAccessToken(m), getAccessToken(m)]);
    await vi.waitFor(() => expect(m.signinSilent).toHaveBeenCalledOnce());
    resolve(user('renewed', 900));
    await expect(both).resolves.toEqual(['renewed', 'renewed']);
  });

  it('оновити не вдалося: прострочений токен — null (401 веде на вхід), ще дійсний — сам токен', async () => {
    await expect(getAccessToken(manager(user('old', -60), () => Promise.reject(new Error('invalid_grant'))))).resolves.toBeNull();
    await expect(getAccessToken(manager(user('almost', 10), () => Promise.reject(new Error('offline'))))).resolves.toBe('almost');
    await expect(getAccessToken(manager(user('old', -60, undefined), () => Promise.resolve(null)))).resolves.toBeNull();
    await expect(getAccessToken(manager(null, () => Promise.resolve(null)))).resolves.toBeNull();
  });
});

describe('незбережена зміна після повторного входу', () => {
  it('позначка показується один раз', () => {
    noteUnsavedChange();
    expect(takeUnsavedChange()).toBe(true);
    expect(takeUnsavedChange()).toBe(false);
  });
});
