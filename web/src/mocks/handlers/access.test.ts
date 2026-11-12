import { describe, expect, it } from 'vitest';
import type { AccessDto, PasswordLinkDto } from '@/api/access';
import { api } from '@/api/client';
import type { ApiError } from '@/api/errors';
import type { AcceptInviteResponse, InviteInfoDto } from '@/api/types';
import { teacherNamed } from '../fixtures';
import { MOCK_ADMIN_ID } from './access';

const failure = (promise: Promise<unknown>) => promise.then(() => null, (e: unknown) => e as ApiError);
const list = (query: Record<string, string> = {}) => api.get<AccessDto[]>('/admin/access', query);

describe('Доступи (мок)', () => {
  it('список із фільтрами за роллю, станом і текстом', async () => {
    const all = await list();
    expect(all.map((a) => a.username ?? a.email)).toContain('admin');
    expect((await list({ role: 'ADMIN' })).every((a) => a.roles.includes('ADMIN'))).toBe(true);
    expect((await list({ status: 'REVOKED' })).map((a) => a.status)).toEqual(['REVOKED']);
    expect((await list({ q: 'кренц' })).map((a) => a.teacher?.shortName)).toEqual([teacherNamed('Кренцін').shortName]);
  });

  it('надати доступ: роль «Викладач» вимагає викладача, адреса й викладач — лише один раз', async () => {
    const teacher = teacherNamed('Колесник А.');
    expect((await failure(api.post('/admin/access', { email: 'k@vtc.vn.ua', roles: ['TEACHER'] })))?.status).toBe(400);
    const granted = await api.post<AccessDto>('/admin/access', { email: 'K@vtc.vn.ua', roles: ['TEACHER'], teacherId: teacher.id });
    expect(granted).toMatchObject({ email: 'k@vtc.vn.ua', username: null, status: 'ACTIVE', hasPassword: false });
    expect(granted.teacher?.id).toBe(teacher.id);
    expect((await failure(api.post('/admin/access', { email: 'k@vtc.vn.ua', roles: ['ADMIN'], displayName: 'К' })))?.code).toBe('EMAIL_TAKEN');
    expect((await failure(api.post('/admin/access', { email: 'z@vtc.vn.ua', roles: ['TEACHER'], teacherId: teacher.id })))?.code).toBe(
      'TEACHER_ALREADY_LINKED',
    );
  });

  it('свій доступ не відкликати; останнього адміністратора — теж', async () => {
    expect((await failure(api.post(`/admin/access/${MOCK_ADMIN_ID}/revoke`)))?.code).toBe('SELF_REVOKE');
    expect((await failure(api.patch(`/admin/access/${MOCK_ADMIN_ID}`, { roles: ['TEACHER'], teacherId: teacherNamed('Стець').id })))?.code).toBe(
      'LAST_ADMIN',
    );
  });

  it('посилання для пароля: новіше скасовує старе, відкликаний доступ його не отримує', async () => {
    const granted = await api.post<AccessDto>('/admin/access', { email: 'boss@vtc.vn.ua', roles: ['ADMIN'], displayName: 'Директор' });
    const older = await api.post<PasswordLinkDto>(`/admin/access/${granted.id}/password-link`);
    const link = await api.post<PasswordLinkDto>(`/admin/access/${granted.id}/password-link`);
    const token = (url: string) => url.slice(url.lastIndexOf('/') + 1);

    expect((await api.get<InviteInfoDto>(`/public/invites/${token(older.url)}`, undefined, { auth: false })).status).toBe('REVOKED');
    expect(await api.get<InviteInfoDto>(`/public/invites/${token(link.url)}`, undefined, { auth: false })).toMatchObject({
      valid: true,
      kind: 'PASSWORD',
      teacherName: 'Директор',
      username: null,
    });
    const short = await failure(api.post(`/public/invites/${token(link.url)}/accept`, { username: 'boss', password: 'short' }, { auth: false }));
    expect(short?.status).toBe(400);
    const done = await api.post<AcceptInviteResponse>(
      `/public/invites/${token(link.url)}/accept`,
      { username: 'boss', password: 'Long-enough-pass-1' },
      { auth: false },
    );
    expect(done.username).toBe('boss');

    await api.post(`/admin/access/${granted.id}/revoke`);
    expect((await failure(api.post(`/admin/access/${granted.id}/password-link`)))?.code).toBe('ACCOUNT_REVOKED');
    await api.post(`/admin/access/${granted.id}/restore`);
    expect((await list({ q: 'boss@' }))[0]).toMatchObject({ status: 'ACTIVE', username: 'boss', hasPassword: true });
  });

  it('запрошення викладачів обробляє далі handlers/public.ts', async () => {
    expect((await api.get<InviteInfoDto>('/public/invites/demo', undefined, { auth: false })).kind).toBe('INVITE');
  });
});
