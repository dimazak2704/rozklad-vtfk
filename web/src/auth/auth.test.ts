import { describe, expect, it } from 'vitest';
import { canAccessPath, isSafeReturnPath, postLoginPath, returnToFromState } from './postLogin';
import { decodeJwtPayload, parseRoleList, rolesFromAccessToken } from './roles';

function fakeJwt(payload: object): string {
  const encode = (value: object) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(payload)}.signature`;
}

describe('ролі з токена Keycloak', () => {
  it('бере лише відомі ролі з realm_access.roles', () => {
    const token = fakeJwt({
      sub: 'u1',
      name: 'Микола Кренцін',
      realm_access: { roles: ['TEACHER', 'offline_access', 'uma_authorization'] },
    });
    expect(rolesFromAccessToken(token)).toEqual(['TEACHER']);
    expect(decodeJwtPayload(token)?.name).toBe('Микола Кренцін');
  });

  it('повертає порожній список для зіпсованого токена', () => {
    expect(rolesFromAccessToken('not-a-jwt')).toEqual([]);
    expect(rolesFromAccessToken(fakeJwt({ realm_access: 'oops' }))).toEqual([]);
  });

  it('розбирає список ролей для dev-режиму', () => {
    expect(parseRoleList('admin, TEACHER,unknown')).toEqual(['ADMIN', 'TEACHER']);
    expect(parseRoleList(undefined)).toEqual([]);
  });
});

describe('куди вести після входу', () => {
  it('ADMIN → /admin, TEACHER → /me', () => {
    expect(postLoginPath(['ADMIN'])).toBe('/admin');
    expect(postLoginPath(['TEACHER'])).toBe('/me');
    expect(postLoginPath([])).toBe('/');
  });

  it('повертає на сторінку, з якої прийшли, якщо вона дозволена', () => {
    expect(postLoginPath(['ADMIN'], '/admin/orders?date=2026-10-19')).toBe('/admin/orders?date=2026-10-19');
    expect(postLoginPath(['TEACHER'], '/me/absences')).toBe('/me/absences');
  });

  it('не веде викладача в адмінку', () => {
    expect(postLoginPath(['TEACHER'], '/admin/changes')).toBe('/me');
    expect(canAccessPath('/admin', ['TEACHER'])).toBe(false);
    expect(canAccessPath('/me', ['ADMIN'])).toBe(true);
  });

  it('відкидає зовнішні й службові адреси', () => {
    expect(isSafeReturnPath('//evil.example')).toBe(false);
    expect(isSafeReturnPath('https://evil.example')).toBe(false);
    expect(postLoginPath(['ADMIN'], '/auth/callback?code=1')).toBe('/admin');
    expect(returnToFromState({ returnTo: '//x' })).toBeUndefined();
    expect(returnToFromState({ returnTo: '/g/4КІ1' })).toBe('/g/4КІ1');
  });
});
