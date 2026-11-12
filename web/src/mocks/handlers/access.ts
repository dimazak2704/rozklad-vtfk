/**
 * Мок-API «Доступів» (core `/api/admin/access`) і посилань для пароля (`/password/:token`). Правила — як у core
 * і сервісі входу: роль «Викладач» разом з викладачем, одна адреса — один доступ, хоча б один адміністратор,
 * який може увійти, власний доступ не відкликати, відкликаний доступ повертає лише «Повернути доступ».
 */
import { http, HttpResponse } from 'msw';
import type { AccessDto, AccessRole, AccessStatus, AccessTeacher, GrantAccessRequest, UpdateAccessRequest } from '@/api/access';
import type { AcceptInviteRequest, InviteInfoDto, IsoInstant, Uuid } from '@/api/types';
import { TEACHERS } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, businessRule, notFound, problem, readJson } from '../kit';
import { createMockStore } from '../store';

/** Фіксовані id демо-акаунтів (ті самі, що в сервісі входу й handlers/me.ts). */
export const MOCK_ADMIN_ID: Uuid = 'a0000000-0000-4000-8000-000000000001';
const KRENTSIN_ID: Uuid = 'a0000000-0000-4000-8000-000000000002';
const NEPYIVODA_ID: Uuid = 'a0000000-0000-4000-8000-000000000003';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const USERNAME_RE = /^[a-z][a-z0-9._-]{2,49}$/;
const ROLES: readonly AccessRole[] = ['ADMIN', 'TEACHER'];

interface MockAccount extends Omit<AccessDto, 'teacher'> {
  teacherId: Uuid | null;
}

interface PasswordLink {
  accountId: Uuid;
  expiresAt: IsoInstant;
  usedAt: IsoInstant | null;
  revoked: boolean;
}

interface AccessState {
  accounts: Map<Uuid, MockAccount>;
  links: Map<string, PasswordLink>;
  nextLink: number;
}

function teacherByName(shortName: string): AccessTeacher {
  const teacher = TEACHERS.find((t) => t.shortName === shortName || t.shortName.startsWith(`${shortName} `));
  if (!teacher) throw new Error(`Немає викладача ${shortName} у фікстурах`);
  return { id: teacher.id, shortName: teacher.shortName, displayName: teacher.displayName };
}

function teacherById(id: Uuid): AccessTeacher | null {
  const teacher = TEACHERS.find((t) => t.id === id);
  return teacher ? { id: teacher.id, shortName: teacher.shortName, displayName: teacher.displayName } : null;
}

function account(init: Partial<MockAccount> & Pick<MockAccount, 'id' | 'displayName' | 'roles'>): MockAccount {
  return {
    username: null,
    email: null,
    status: 'ACTIVE',
    hasPassword: false,
    google: { linked: false },
    demo: false,
    lastLoginAt: null,
    teacherId: null,
    ...init,
  };
}

/** Демо: адміністратор і два викладачі з паролем, доступ за e-mail через Google, один відкликаний. */
const accessState = createMockStore<AccessState>(() => {
  const day = 86_400_000;
  const ago = (days: number) => new Date(Date.now() - days * day).toISOString();
  const seeded = [
    account({
      id: MOCK_ADMIN_ID,
      username: 'admin',
      email: 'admin@rozklad.local',
      displayName: 'Диспетчер Розкладу',
      roles: ['ADMIN'],
      hasPassword: true,
      demo: true,
      lastLoginAt: ago(0),
    }),
    account({
      id: KRENTSIN_ID,
      username: 'krentsin',
      email: 'krentsin@rozklad.local',
      displayName: 'М. Д. Кренцін',
      roles: ['TEACHER'],
      hasPassword: true,
      demo: true,
      lastLoginAt: ago(1),
      teacherId: teacherByName('Кренцін').id,
    }),
    account({
      id: NEPYIVODA_ID,
      username: 'nepyivoda',
      email: 'nepyivoda@rozklad.local',
      displayName: 'М. В. Непийвода',
      roles: ['TEACHER'],
      hasPassword: true,
      demo: true,
      teacherId: teacherByName('Непийвода').id,
    }),
    account({
      id: mockId('account', 'prokopchuk'),
      email: 'prokopchuk@vtc.vn.ua',
      displayName: 'Прокопчук',
      roles: ['TEACHER'],
      google: { linked: true },
      lastLoginAt: ago(3),
      teacherId: teacherByName('Прокопчук').id,
    }),
    account({
      id: mockId('account', 'former'),
      email: 'former.dispatcher@vtc.vn.ua',
      displayName: 'Колишній диспетчер',
      roles: ['ADMIN'],
      status: 'REVOKED',
      google: { linked: true },
      lastLoginAt: ago(40),
    }),
  ];
  return { accounts: new Map(seeded.map((a) => [a.id, a])), links: new Map(), nextLink: 1 };
});

function toDto(stored: MockAccount): AccessDto {
  const { teacherId, ...rest } = stored;
  return { ...rest, roles: [...rest.roles].sort(), teacher: teacherId ? teacherById(teacherId) : null };
}

function usableAdmin(a: MockAccount): boolean {
  return a.status === 'ACTIVE' && a.roles.includes('ADMIN') && (a.hasPassword || a.google.linked);
}

/** Зміна прибрала б останнього адміністратора, який може увійти. */
function wouldLeaveNoAdmin(state: AccessState, target: MockAccount, after: MockAccount): boolean {
  if (!usableAdmin(target) || usableAdmin(after)) return false;
  return ![...state.accounts.values()].some((other) => other.id !== target.id && usableAdmin(other));
}

const lastAdmin = () =>
  problem({ status: 409, code: 'LAST_ADMIN', detail: 'Має залишитися хоча б один адміністратор, який може увійти.' });

function teacherTaken(state: AccessState, teacherId: Uuid, except: Uuid | null): MockAccount | undefined {
  return [...state.accounts.values()].find((a) => a.teacherId === teacherId && a.id !== except);
}

function teacherRules(roles: readonly AccessRole[], teacherId: Uuid | null | undefined): Response | null {
  if (roles.includes('TEACHER') && !teacherId) {
    return badRequest('Перевірте поля.', [{ field: 'teacherId', message: 'Для ролі «Викладач» виберіть викладача.' }]);
  }
  if (!roles.includes('TEACHER') && teacherId) {
    return businessRule('TEACHER_ROLE_REQUIRED', 'Викладача можна прив’язати лише з роллю «Викладач».');
  }
  if (teacherId && !teacherById(teacherId)) {
    return badRequest('Перевірте поля.', [{ field: 'teacherId', message: 'Викладача не знайдено.' }]);
  }
  return null;
}

function validRoles(value: unknown): value is AccessRole[] {
  return Array.isArray(value) && value.length > 0 && value.every((role) => ROLES.includes(role as AccessRole));
}

function linkInfo(link: PasswordLink, owner: MockAccount, now: Date): InviteInfoDto {
  const expired = new Date(link.expiresAt).getTime() <= now.getTime();
  const status =
    link.usedAt !== null ? 'USED' : link.revoked || owner.status === 'REVOKED' ? 'REVOKED' : expired ? 'EXPIRED' : 'ACTIVE';
  return {
    valid: status === 'ACTIVE',
    status,
    teacherName: owner.displayName,
    expiresAt: link.expiresAt,
    kind: 'PASSWORD',
    username: owner.username,
  };
}

export const handlers = [
  http.get(apiPath('/admin/access'), ({ request }) => {
    const url = new URL(request.url);
    const q = (url.searchParams.get('q') ?? '').trim().toLocaleLowerCase('uk');
    const role = url.searchParams.get('role') as AccessRole | null;
    const status = url.searchParams.get('status') as AccessStatus | null;
    const list = [...accessState.get().accounts.values()]
      .map(toDto)
      .filter((a) => !role || a.roles.includes(role))
      .filter((a) => !status || a.status === status)
      .filter(
        (a) =>
          !q ||
          [a.displayName, a.email, a.username, a.teacher?.shortName].some((v) => v?.toLocaleLowerCase('uk').includes(q)),
      )
      .sort((a, b) => a.displayName.localeCompare(b.displayName, 'uk'));
    return HttpResponse.json(list);
  }),

  http.post(apiPath('/admin/access'), async ({ request }) => {
    const state = accessState.get();
    const body = await readJson<Partial<GrantAccessRequest>>(request);
    const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
    const errors = [
      ...(EMAIL_RE.test(email) ? [] : [{ field: 'email', message: 'Перевірте адресу e-mail' }]),
      ...(validRoles(body.roles) ? [] : [{ field: 'roles', message: 'Виберіть роль' }]),
    ];
    if (errors.length > 0) return badRequest('Перевірте поля.', errors);
    const roles = body.roles as AccessRole[];
    const rule = teacherRules(roles, body.teacherId);
    if (rule) return rule;
    const teacher = body.teacherId ? teacherById(body.teacherId) : null;
    const displayName = body.displayName?.trim() || teacher?.displayName;
    if (!displayName) return badRequest('Перевірте поля.', [{ field: 'displayName', message: 'Вкажіть ім’я.' }]);
    if (teacher && teacherTaken(state, teacher.id, null)) {
      return problem({ status: 409, code: 'TEACHER_ALREADY_LINKED', detail: `Викладач ${teacher.shortName} уже має обліковий запис.` });
    }
    if ([...state.accounts.values()].some((a) => a.email === email)) {
      return problem({ status: 409, code: 'EMAIL_TAKEN', detail: 'Ця адреса вже має доступ.' });
    }
    const created = account({ id: crypto.randomUUID(), email, displayName, roles, teacherId: teacher?.id ?? null });
    state.accounts.set(created.id, created);
    return HttpResponse.json(toDto(created), { status: 201, headers: { Location: `/api/admin/access/${created.id}` } });
  }),

  http.patch(apiPath('/admin/access/:id'), async ({ params, request }) => {
    const state = accessState.get();
    const current = state.accounts.get(String(params.id));
    if (!current) return notFound('Обліковий запис не знайдено.');
    const body = await readJson<UpdateAccessRequest>(request);
    if (body.roles !== undefined && !validRoles(body.roles)) {
      return badRequest('Перевірте поля.', [{ field: 'roles', message: 'Виберіть роль' }]);
    }
    const roles = body.roles ?? current.roles;
    const teacherId = roles.includes('TEACHER') ? (body.teacherId ?? current.teacherId) : null;
    const rule = teacherRules(roles, roles.includes('TEACHER') ? teacherId : body.teacherId);
    if (rule) return rule;
    if (teacherId && teacherTaken(state, teacherId, current.id)) {
      return problem({ status: 409, code: 'TEACHER_ALREADY_LINKED', detail: 'Цей викладач уже має обліковий запис.' });
    }
    const updated: MockAccount = { ...current, roles, teacherId, displayName: body.displayName?.trim() || current.displayName };
    if (wouldLeaveNoAdmin(state, current, updated)) return lastAdmin();
    state.accounts.set(updated.id, updated);
    return HttpResponse.json(toDto(updated));
  }),

  http.post(apiPath('/admin/access/:id/revoke'), ({ params }) => {
    const state = accessState.get();
    const current = state.accounts.get(String(params.id));
    if (!current) return notFound('Обліковий запис не знайдено.');
    if (current.id === MOCK_ADMIN_ID) return businessRule('SELF_REVOKE', 'Не можна відкликати власний доступ.');
    const updated: MockAccount = { ...current, status: 'REVOKED' };
    if (wouldLeaveNoAdmin(state, current, updated)) return lastAdmin();
    state.accounts.set(updated.id, updated);
    for (const link of state.links.values()) if (link.accountId === updated.id && !link.usedAt) link.revoked = true;
    return HttpResponse.json(toDto(updated));
  }),

  http.post(apiPath('/admin/access/:id/restore'), ({ params }) => {
    const state = accessState.get();
    const current = state.accounts.get(String(params.id));
    if (!current) return notFound('Обліковий запис не знайдено.');
    const updated: MockAccount = { ...current, status: 'ACTIVE' };
    state.accounts.set(updated.id, updated);
    return HttpResponse.json(toDto(updated));
  }),

  http.delete(apiPath('/admin/access/:id/google'), ({ params }) => {
    const state = accessState.get();
    const current = state.accounts.get(String(params.id));
    if (!current) return notFound('Обліковий запис не знайдено.');
    const updated: MockAccount = { ...current, google: { linked: false } };
    if (wouldLeaveNoAdmin(state, current, updated)) return lastAdmin();
    state.accounts.set(updated.id, updated);
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(apiPath('/admin/access/:id/password-link'), ({ params, request }) => {
    const state = accessState.get();
    const current = state.accounts.get(String(params.id));
    if (!current) return notFound('Обліковий запис не знайдено.');
    if (current.status === 'REVOKED') return problem({ status: 409, code: 'ACCOUNT_REVOKED', detail: 'Спершу поверніть доступ.' });
    for (const link of state.links.values()) if (link.accountId === current.id && !link.usedAt) link.revoked = true;
    const token = `demo-password-${state.nextLink}`;
    state.nextLink += 1;
    const expiresAt = new Date(Date.now() + 7 * 86_400_000).toISOString();
    state.links.set(token, { accountId: current.id, expiresAt, usedAt: null, revoked: false });
    const origin = new URL(request.url).origin;
    return HttpResponse.json({ url: `${origin}/password/${token}`, expiresAt }, { status: 201 });
  }),

  // посилання для пароля; інші токени обробляє handlers/public.ts (запрошення викладачів)
  http.get(apiPath('/public/invites/:token'), ({ params }) => {
    const state = accessState.get();
    const link = state.links.get(String(params.token));
    const owner = link ? state.accounts.get(link.accountId) : undefined;
    if (!link || !owner) return undefined;
    return HttpResponse.json(linkInfo(link, owner, new Date()));
  }),

  http.post(apiPath('/public/invites/:token/accept'), async ({ params, request }) => {
    const state = accessState.get();
    const link = state.links.get(String(params.token));
    const owner = link ? state.accounts.get(link.accountId) : undefined;
    if (!link || !owner) return undefined;
    const info = linkInfo(link, owner, new Date());
    if (owner.status === 'REVOKED') {
      return problem({ status: 410, slug: 'invite-gone', detail: 'Доступ відкликано. Зверніться до навчальної частини.' });
    }
    if (!info.valid) return problem({ status: 410, slug: 'invite-gone', detail: 'Посилання вже недійсне. Попросіть нове.' });
    const body = await readJson<Partial<AcceptInviteRequest>>(request);
    const username = typeof body.username === 'string' ? body.username.trim().toLowerCase() : '';
    const password = typeof body.password === 'string' ? body.password : '';
    const errors = [
      ...(USERNAME_RE.test(username) ? [] : [{ field: 'username', message: 'Від 3 до 50 латинських літер, цифр або «.», «_», «-»' }]),
      ...(password.length >= 12 && password.length <= 64 ? [] : [{ field: 'password', message: 'Пароль — від 12 до 64 символів' }]),
    ];
    if (errors.length > 0) return badRequest('Перевірте логін і пароль.', errors);
    if (owner.username && owner.username !== username) {
      return problem({ status: 409, detail: `Ваше ім’я користувача — «${owner.username}». Вкажіть саме його.` });
    }
    if (!owner.username && [...state.accounts.values()].some((a) => a.username === username)) {
      return problem({ status: 409, detail: `Ім’я користувача «${username}» уже зайняте. Виберіть інше.` });
    }
    state.accounts.set(owner.id, { ...owner, username, hasPassword: true, status: 'ACTIVE' });
    link.usedAt = new Date().toISOString();
    return HttpResponse.json({ username }, { status: 201 });
  }),
];
