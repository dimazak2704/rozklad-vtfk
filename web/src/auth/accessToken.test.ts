import { ErrorResponse, ErrorTimeout, type User } from 'oidc-client-ts';
import { describe, expect, it, vi } from 'vitest';
import { takeUnsavedChange, noteUnsavedChange } from './unsavedChange';
import { forgetEndedSession, getAccessToken, isSessionEnded, renewSession } from './userManager';

/** Токен без refresh token: сервіс входу публічному клієнту його не видає, оновлення — через iframe. */
function user(token: string, expiresIn: number): User {
  return { access_token: token, expires_in: expiresIn, expired: expiresIn <= 0 } as unknown as User;
}

function manager(current: User | null, renewed: () => Promise<User | null>) {
  return {
    getUser: vi.fn(() => Promise.resolve(current)),
    signinSilent: vi.fn(renewed),
    removeUser: vi.fn(() => Promise.resolve()),
  };
}

const loginRequired = () => new ErrorResponse({ error: 'login_required', error_description: 'no session' });

describe('getAccessToken', () => {
  it('дійсний токен віддає без оновлення', async () => {
    const m = manager(user('fresh', 600), () => Promise.resolve(null));
    await expect(getAccessToken(m)).resolves.toBe('fresh');
    expect(m.signinSilent).not.toHaveBeenCalled();
  });

  it('токен, що закінчився (ноутбук спав), спершу тихо оновлює без refresh token-а, а не шле запит без нього', async () => {
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
    await expect(getAccessToken(manager(user('old', -60), () => Promise.resolve(null)))).resolves.toBeNull();
    await expect(getAccessToken(manager(null, () => Promise.resolve(null)))).resolves.toBeNull();
  });

  it('сесії входу немає (login_required): користувача забуває, навіть якщо токен ще трохи дійсний', async () => {
    const m = manager(user('almost', 10), () => Promise.reject(loginRequired()));
    await expect(getAccessToken(m)).resolves.toBeNull();
    expect(m.removeUser).toHaveBeenCalledOnce();
  });

  it('мережевий збій оновлення користувача не забуває', async () => {
    const m = manager(user('almost', 10), () => Promise.reject(new ErrorTimeout('IFrame timed out without a response')));
    await expect(getAccessToken(m)).resolves.toBe('almost');
    expect(m.removeUser).not.toHaveBeenCalled();
  });
});

describe('кінець сесії входу', () => {
  it('розпізнає відповіді prompt=none без сесії', () => {
    expect(isSessionEnded(loginRequired())).toBe(true);
    expect(isSessionEnded(new ErrorResponse({ error: 'interaction_required' }))).toBe(true);
    expect(isSessionEnded(new ErrorResponse({ error: 'server_error' }))).toBe(false);
    expect(isSessionEnded(new TypeError('Failed to fetch'))).toBe(false);
    expect(isSessionEnded(null)).toBe(false);
  });

  it('фонове оновлення без сесії забуває користувача, інші помилки — ні', () => {
    const removeUser = vi.fn(() => Promise.resolve());
    forgetEndedSession(new ErrorTimeout('IFrame timed out without a response'), { removeUser });
    expect(removeUser).not.toHaveBeenCalled();
    forgetEndedSession(loginRequired(), { removeUser });
    expect(removeUser).toHaveBeenCalledOnce();
  });
});

describe('незбережена зміна після повторного входу', () => {
  it('позначка показується один раз', () => {
    noteUnsavedChange();
    expect(takeUnsavedChange()).toBe(true);
    expect(takeUnsavedChange()).toBe(false);
  });
});

describe('renewSession (повернення з повторного входу через Google)', () => {
  it('оновлює токени одразу, навіть якщо поточні ще дійсні', async () => {
    const m = manager(user('fresh', 600), () => Promise.resolve(user('renewed', 900)));
    await renewSession(m);
    expect(m.signinSilent).toHaveBeenCalledOnce();
    expect(m.removeUser).not.toHaveBeenCalled();
  });

  it('паралельні виклики (меню в шапці й бічній панелі) дають одне оновлення', async () => {
    let resolve: (value: User) => void = () => undefined;
    const m = manager(user('fresh', 600), () => new Promise<User>((done) => (resolve = done)));
    const both = Promise.all([renewSession(m), renewSession(m)]);
    await vi.waitFor(() => expect(m.signinSilent).toHaveBeenCalledOnce());
    resolve(user('renewed', 900));
    await both;
    expect(m.signinSilent).toHaveBeenCalledOnce();
  });

  it('сесії немає — користувача забуває; мережевий збій — ні, і помилка не вилітає', async () => {
    const ended = manager(user('fresh', 600), () => Promise.reject(loginRequired()));
    await expect(renewSession(ended)).resolves.toBeUndefined();
    expect(ended.removeUser).toHaveBeenCalledOnce();

    const offline = manager(user('fresh', 600), () => Promise.reject(new ErrorTimeout('IFrame timed out without a response')));
    await expect(renewSession(offline)).resolves.toBeUndefined();
    expect(offline.removeUser).not.toHaveBeenCalled();
  });
});
