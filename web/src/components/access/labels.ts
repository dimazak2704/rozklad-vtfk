import type { AccessDto, AccessRole, AccessStatus } from '@/api/access';
import type { StatusKind } from '@/components/ui';

export const ROLE_LABEL: Readonly<Record<AccessRole, string>> = {
  ADMIN: 'Адміністратор',
  TEACHER: 'Викладач',
};

/** Ролі через кому в сталому порядку: «Адміністратор, викладач». */
export function rolesText(roles: readonly AccessRole[]): string {
  const order: AccessRole[] = ['ADMIN', 'TEACHER'];
  const text = order.filter((role) => roles.includes(role)).map((role) => ROLE_LABEL[role].toLowerCase()).join(', ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export const STATUS_LABEL: Readonly<Record<AccessStatus, string>> = {
  ACTIVE: 'Активний',
  REVOKED: 'Відкликано',
  DISABLED: 'Вимкнено',
};

export const STATUS_KIND: Readonly<Record<AccessStatus, StatusKind>> = {
  ACTIVE: 'done',
  REVOKED: 'rejected',
  DISABLED: 'pending',
};

/** Як людина входить: «Google і пароль», «Google», «пароль» або ще ніяк (доступ є, входу ще не було). */
export function signInText(access: Pick<AccessDto, 'google' | 'hasPassword'>): string | null {
  if (access.google.linked && access.hasPassword) return 'Google і пароль';
  if (access.google.linked) return 'Google';
  if (access.hasPassword) return 'Пароль';
  return null;
}

/** Логін або e-mail — те, що людина вводить на сторінці входу. */
export function loginName(access: Pick<AccessDto, 'username' | 'email'>): string {
  return access.username ?? access.email ?? '';
}
