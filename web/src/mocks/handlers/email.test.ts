import { describe, expect, it } from 'vitest';
import { api } from '@/api/client';
import { emailPreferencesRequest } from '@/api/email';
import { ApiError } from '@/api/errors';
import type { EmailDeliveryDto, EmailPreferencesDto, EmailStatusDto, Page } from '@/api/types';

const getPrefs = () => api.get<EmailPreferencesDto>('/notify/email/me');
const putPrefs = (body: unknown) => api.put<EmailPreferencesDto>('/notify/email/me', body);

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('запит мав завершитися помилкою');
}

const kind = (prefs: EmailPreferencesDto, name: string) => prefs.kinds.find((k) => k.kind === name);

describe('GET/PUT /api/notify/email/me', () => {
  it('типово: листи увімкнено, адреса підтверджена, розпорядження й відсутності — вимкнені', async () => {
    const prefs = await getPrefs();
    expect(prefs).toMatchObject({ enabled: true, addressStatus: 'VERIFIED', verifiedBy: 'GOOGLE', channel: { enabled: true } });
    expect(kind(prefs, 'SUBSTITUTION_NOTICE')).toMatchObject({ audience: 'TEACHER', enabled: true, defaultEnabled: true });
    expect(kind(prefs, 'ORDER_TEACHER')).toMatchObject({ enabled: false, defaultEnabled: false });
    expect(kind(prefs, 'ABSENCE_REPORTED')).toMatchObject({ audience: 'ADMIN', enabled: false });
    expect(kind(prefs, 'SECURITY_TELEGRAM_RELINKED')).toMatchObject({ switchable: false, enabled: true });
  });

  it('PUT зберігає стан і повертає новий; незгаданий вид лишається як був', async () => {
    const prefs = await getPrefs();
    const saved = await putPrefs({ enabled: false, kinds: { ORDER_TEACHER: true, GENERATION_FINISHED: false } });
    expect(saved.enabled).toBe(false);
    expect(kind(saved, 'ORDER_TEACHER')?.enabled).toBe(true);
    expect(kind(saved, 'GENERATION_FINISHED')?.enabled).toBe(false);
    expect(kind(saved, 'SUBSTITUTION_NOTICE')?.enabled).toBe(true);
    expect(saved.version).toBe((prefs.version ?? 0) + 1);
    expect(await getPrefs()).toEqual(saved);
  });

  it('тіло, яке шле SPA, — стан усіх перемикачів, крім листа безпеки', async () => {
    const body = emailPreferencesRequest(await getPrefs());
    expect(body.kinds).not.toHaveProperty('SECURITY_TELEGRAM_RELINKED');
    expect(Object.keys(body.kinds)).toHaveLength(12);
    expect(await putPrefs(body)).toMatchObject({ enabled: true });
  });

  it('невідомий вид — 400 з полем; лист безпеки — 422; застаріла версія — 409', async () => {
    const unknown = await failure(putPrefs({ enabled: true, kinds: { NOPE: true } }));
    expect(unknown.status).toBe(400);
    expect(unknown.fieldErrors()).toHaveProperty(['kinds.NOPE']);

    const locked = await failure(putPrefs({ enabled: true, kinds: { SECURITY_TELEGRAM_RELINKED: false } }));
    expect(locked).toMatchObject({ status: 422, code: 'email-kind-not-allowed' });

    await putPrefs({ enabled: true, kinds: {}, version: 0 });
    expect((await failure(putPrefs({ enabled: true, kinds: {}, version: 0 }))).status).toBe(409);
  });
});

describe('POST /api/notify/email/me/test', () => {
  it('202 і запис у журналі; четвертий за годину — 429', async () => {
    for (let i = 0; i < 3; i += 1) expect(await api.post('/notify/email/me/test')).toEqual({ status: 'QUEUED' });
    expect((await failure(api.post('/notify/email/me/test'))).status).toBe(429);
    const log = await api.get<Page<EmailDeliveryDto>>('/notify/email/deliveries', { kind: 'TEST' });
    expect(log.total).toBe(3);
    expect(log.items[0]).toMatchObject({ kind: 'TEST', status: 'SENT', to: 'k•••@vtc.vn.ua' });
  });
});

describe('стан пошти й журнал', () => {
  it('стан без логіна й пароля; стенд мока — Mailpit', async () => {
    const status = await api.get<EmailStatusDto>('/notify/email/status');
    expect(status).toMatchObject({ enabled: true, mode: 'DEV_CATCHER', host: 'mailpit', port: 1025 });
    expect(Object.keys(status).join(',')).not.toMatch(/user|password/i);
  });

  it('журнал: нові спершу, адреси під маскою, фільтр стану, 400 на невідомий стан', async () => {
    const page = await api.get<Page<EmailDeliveryDto>>('/notify/email/deliveries');
    expect(page.items.map((d) => d.createdAt)).toEqual([...page.items.map((d) => d.createdAt)].sort().reverse());
    expect(page.items.every((d) => d.to.includes('•••'))).toBe(true);
    const failed = await api.get<Page<EmailDeliveryDto>>('/notify/email/deliveries', { status: 'FAILED' });
    expect(failed.items.map((d) => d.status)).toEqual(['FAILED']);
    expect((await failure(api.get('/notify/email/deliveries', { status: 'LOST' }))).status).toBe(400);
  });
});
