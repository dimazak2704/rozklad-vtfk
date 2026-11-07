import { useMemo } from 'react';
import { useAuth } from 'react-oidc-context';
import type { Role } from '@/api/types';
import { parseRoleList, rolesFromAccessToken } from './roles';
import type { SigninState } from './postLogin';

export interface CurrentUser {
  /** `sub` з токена Keycloak. */
  id: string;
  username: string;
  /** Імʼя для показу: «Микола Кренцін» або логін. */
  name: string;
  email: string | null;
  roles: Role[];
}

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface CurrentUserState {
  status: AuthStatus;
  user: CurrentUser | null;
  roles: Role[];
  isAdmin: boolean;
  isTeacher: boolean;
  /** Чи має користувач хоча б одну з ролей. */
  hasRole: (...roles: Role[]) => boolean;
  signIn: (returnTo?: string) => Promise<void>;
  signOut: () => Promise<void>;
  error: Error | null;
  /** Dev-режим без Keycloak (VITE_DEV_AUTH_ROLES). У production-збірці завжди false. */
  isDevBypass: boolean;
}

// import.meta.env.DEV у production-збірці замінюється на false, і гілка зникає з бандла
const DEV_ROLES: Role[] = import.meta.env.DEV ? parseRoleList(import.meta.env.VITE_DEV_AUTH_ROLES) : [];

function displayName(profile: Record<string, unknown>, fallback: string): string {
  const name = typeof profile.name === 'string' ? profile.name.trim() : '';
  if (name) return name;
  const parts = [profile.given_name, profile.family_name].filter((p): p is string => typeof p === 'string' && p !== '');
  return parts.length > 0 ? parts.join(' ') : fallback;
}

/** Поточний користувач і ролі з `realm_access.roles` access-токена. */
export function useCurrentUser(): CurrentUserState {
  const auth = useAuth();

  return useMemo<CurrentUserState>(() => {
    const signIn = async (returnTo?: string) => {
      const state: SigninState = { returnTo };
      await auth.signinRedirect({ state });
    };
    const signOut = async () => {
      await auth.signoutRedirect();
    };

    if (DEV_ROLES.length > 0) {
      const user: CurrentUser = { id: 'dev-user', username: 'dev', name: 'Розробник (dev)', email: null, roles: DEV_ROLES };
      return {
        status: 'authenticated',
        user,
        roles: DEV_ROLES,
        isAdmin: DEV_ROLES.includes('ADMIN'),
        isTeacher: DEV_ROLES.includes('TEACHER'),
        hasRole: (...roles) => roles.some((r) => DEV_ROLES.includes(r)),
        signIn: () => Promise.resolve(),
        signOut: () => {
          window.location.assign('/');
          return Promise.resolve();
        },
        error: null,
        isDevBypass: true,
      };
    }

    const oidcUser = auth.user;
    const authenticated = auth.isAuthenticated && oidcUser != null;
    const status: AuthStatus = auth.isLoading ? 'loading' : authenticated ? 'authenticated' : 'anonymous';
    const roles = authenticated ? rolesFromAccessToken(oidcUser.access_token) : [];
    const profile = (oidcUser?.profile ?? {}) as Record<string, unknown>;
    const username = typeof profile.preferred_username === 'string' ? profile.preferred_username : '';
    const user: CurrentUser | null = authenticated
      ? {
          id: oidcUser.profile.sub,
          username,
          name: displayName(profile, username || 'Користувач'),
          email: typeof profile.email === 'string' ? profile.email : null,
          roles,
        }
      : null;

    return {
      status,
      user,
      roles,
      isAdmin: roles.includes('ADMIN'),
      isTeacher: roles.includes('TEACHER'),
      hasRole: (...wanted) => wanted.some((r) => roles.includes(r)),
      signIn,
      signOut,
      error: auth.error ?? null,
      isDevBypass: false,
    };
  }, [auth]);
}
