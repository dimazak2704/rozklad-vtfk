/**
 * «Доступи» (core `/api/admin/access`, ADMIN): хто може увійти і з якою роллю. Обліковий запис живе в сервісі
 * входу `auth`; доступ за e-mail — це запис без пароля, з яким людина входить через Google або за посиланням
 * для пароля. Помилки 409 мають `code`: EMAIL_TAKEN, TEACHER_ALREADY_LINKED, LAST_ADMIN, ACCOUNT_REVOKED.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { IsoInstant, Nullable, Uuid } from './types';

export type AccessRole = 'ADMIN' | 'TEACHER';
/** ACTIVE — може увійти; REVOKED — доступ відкликано (повертає лише «Повернути доступ»); DISABLED — вимкнено. */
export type AccessStatus = 'ACTIVE' | 'REVOKED' | 'DISABLED';

export interface AccessTeacher {
  id: Uuid;
  shortName: string;
  displayName: string;
}

export interface AccessDto {
  id: Uuid;
  /** Немає, доки людина з доступом за e-mail не вибрала логін (посилання для пароля). */
  username: Nullable<string>;
  email: Nullable<string>;
  displayName: string;
  roles: AccessRole[];
  status: AccessStatus;
  hasPassword: boolean;
  google: { linked: boolean };
  demo: boolean;
  lastLoginAt: Nullable<IsoInstant>;
  teacher: Nullable<AccessTeacher>;
}

export interface AccessQuery {
  q?: string;
  role?: AccessRole;
  status?: AccessStatus;
}

export interface GrantAccessRequest {
  email: string;
  displayName?: string;
  roles: AccessRole[];
  teacherId?: Uuid;
}

export interface UpdateAccessRequest {
  roles?: AccessRole[];
  displayName?: string;
  teacherId?: Uuid;
}

/** Посилання для пароля: показується один раз, на сервері лише його хеш. */
export interface PasswordLinkDto {
  url: string;
  expiresAt: IsoInstant;
}

export const accessKeys = {
  all: () => ['admin', 'access'] as const,
  list: (query: AccessQuery) => ['admin', 'access', 'list', query] as const,
};

export function useAccessList(query: AccessQuery) {
  return useQuery({
    queryKey: accessKeys.list(query),
    queryFn: ({ signal }) => api.get<AccessDto[]>('/admin/access', { ...query }, { signal }),
  });
}

/** Після зміни доступу оновлюємо і список, і довідник викладачів (там видно, хто має вхід). */
function useAccessMutation<V, R>(mutationFn: (variables: V) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: accessKeys.all() });
      void queryClient.invalidateQueries({ queryKey: ['admin', 'catalog'] });
    },
  });
}

export function useGrantAccess() {
  return useAccessMutation((body: GrantAccessRequest) => api.post<AccessDto>('/admin/access', body));
}

export function useUpdateAccess() {
  return useAccessMutation(({ id, body }: { id: Uuid; body: UpdateAccessRequest }) =>
    api.patch<AccessDto>(`/admin/access/${encodeURIComponent(id)}`, body),
  );
}

export function useRevokeAccess() {
  return useAccessMutation((id: Uuid) => api.post<AccessDto>(`/admin/access/${encodeURIComponent(id)}/revoke`));
}

export function useRestoreAccess() {
  return useAccessMutation((id: Uuid) => api.post<AccessDto>(`/admin/access/${encodeURIComponent(id)}/restore`));
}

export function useUnbindGoogle() {
  return useAccessMutation((id: Uuid) => api.delete(`/admin/access/${encodeURIComponent(id)}/google`));
}

export function usePasswordLink() {
  return useMutation({
    mutationFn: (id: Uuid) => api.post<PasswordLinkDto>(`/admin/access/${encodeURIComponent(id)}/password-link`),
  });
}
