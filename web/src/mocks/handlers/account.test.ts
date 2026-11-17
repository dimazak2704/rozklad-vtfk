import { afterEach, describe, expect, it, vi } from 'vitest';
import { resetMockStores } from '../store';
import { MOCK_CURRENT_PASSWORD, MOCK_GOOGLE_REAUTH_STORAGE_KEY, MOCK_PASSWORD_STORAGE_KEY } from './account';

const BASE = 'http://localhost:8088/auth';
const json = { 'Content-Type': 'application/json' };

async function info() {
  return (await (await fetch(`${BASE}/api/account/password`)).json()) as { status: string; reauthRequired: boolean };
}

function setInitial(newPassword: string) {
  return fetch(`${BASE}/api/account/initial-password`, { method: 'POST', headers: json, body: JSON.stringify({ newPassword }) });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('мок власного пароля', () => {
  it('типово пароль є: стан CHANGEABLE, перший пароль — 409', async () => {
    expect(await info()).toMatchObject({ status: 'CHANGEABLE', reauthRequired: false });
    const response = await setInitial('Novyi-parol-2026');
    expect(response.status).toBe(409);
    expect(await response.json()).toMatchObject({ code: 'password-already-set' });
  });

  it('вхід лише через Google: без підтвердження 403, після «Google» — пароль встановлено, далі звичайна зміна', async () => {
    window.sessionStorage.setItem(MOCK_PASSWORD_STORAGE_KEY, 'google');
    resetMockStores();
    expect(await info()).toMatchObject({ status: 'NO_PASSWORD', reauthRequired: true });
    const refused = await setInitial('Novyi-parol-2026');
    expect(refused.status).toBe(403);
    expect(await refused.json()).toMatchObject({ code: 'reauth-required', type: 'urn:rozklad:auth:reauth-required' });

    expect((await fetch(`${BASE}/mock/google-reauth`, { method: 'POST' })).status).toBe(204);
    // підтвердження переживає перезавантаження сторінки мок-стенда
    expect(window.sessionStorage.getItem(MOCK_GOOGLE_REAUTH_STORAGE_KEY)).not.toBeNull();
    resetMockStores();
    expect(await info()).toMatchObject({ status: 'NO_PASSWORD', reauthRequired: false });

    expect((await setInitial('short-1')).status).toBe(400);
    expect((await setInitial('Novyi-parol-2026')).status).toBe(204);
    expect(await info()).toMatchObject({ status: 'CHANGEABLE', reauthRequired: false });
    const change = await fetch(`${BASE}/api/account/password`, {
      method: 'POST',
      headers: json,
      body: JSON.stringify({ currentPassword: 'Novyi-parol-2026', newPassword: MOCK_CURRENT_PASSWORD }),
    });
    expect(change.status).toBe(204);
  });

  it('підтвердження через Google діє 10 хвилин', async () => {
    window.sessionStorage.setItem(MOCK_PASSWORD_STORAGE_KEY, 'google');
    resetMockStores();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-17T10:00:00Z'));
    await fetch(`${BASE}/mock/google-reauth`, { method: 'POST' });
    vi.setSystemTime(new Date('2026-11-17T10:10:01Z'));
    expect(await info()).toMatchObject({ reauthRequired: true });
    expect((await setInitial('Novyi-parol-2026')).status).toBe(403);
  });
});
