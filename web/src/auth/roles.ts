import type { Role } from '@/api/types';

const KNOWN_ROLES: ReadonlySet<string> = new Set<Role>(['ADMIN', 'TEACHER', 'SERVICE']);

/** Розбирає payload JWT без перевірки підпису — лише для інтерфейсу; перевіряє сервер. */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=');
    const binary = atob(base64);
    const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
    const json: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof json === 'object' && json !== null ? (json as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Ролі realm з `realm_access.roles` (Keycloak), лише відомі системі. */
export function rolesFromClaims(claims: Record<string, unknown> | null | undefined): Role[] {
  const realmAccess = claims?.realm_access;
  if (typeof realmAccess !== 'object' || realmAccess === null) return [];
  const roles = (realmAccess as { roles?: unknown }).roles;
  if (!Array.isArray(roles)) return [];
  return roles.filter((role): role is Role => typeof role === 'string' && KNOWN_ROLES.has(role));
}

export function rolesFromAccessToken(token: string | undefined | null): Role[] {
  return token ? rolesFromClaims(decodeJwtPayload(token)) : [];
}

/** Ролі для dev-режиму без Keycloak: `VITE_DEV_AUTH_ROLES=ADMIN,TEACHER`. */
export function parseRoleList(raw: string | undefined): Role[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((r) => r.trim().toUpperCase())
    .filter((role): role is Role => KNOWN_ROLES.has(role));
}
