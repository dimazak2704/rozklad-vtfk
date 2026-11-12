import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { useJournal } from './journal';
import { clearRecordRefs, sameRequest, skipUnchanged, trackSavedRecord } from './records';

const toast = vi.hoisted(() => ({ undo: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn(), success: vi.fn() }));
vi.mock('@/components/ui/toast', () => ({ notify: toast }));

interface Body {
  name: string;
  version?: number;
}

/** Сервер-іграшка: id, версії, 404 на видалений запис і 409 на стару версію. */
function fakeServer() {
  const rows = new Map<string, { name: string; version: number }>();
  let seq = 0;
  const calls: string[] = [];
  return {
    rows,
    calls,
    create: vi.fn((body: Body) => {
      seq += 1;
      const id = `r${seq}`;
      rows.set(id, { name: body.name, version: 0 });
      calls.push(`POST ${id}`);
      return Promise.resolve({ id, version: 0 });
    }),
    update: vi.fn((id: string, body: Body) => {
      const row = rows.get(id);
      calls.push(`PUT ${id} v${body.version}`);
      if (!row) return Promise.reject(new ApiError({ status: 404 }));
      if (row.version !== body.version) return Promise.reject(new ApiError({ status: 409, type: 'urn:rozklad:problem:stale-data' }));
      row.name = body.name;
      row.version += 1;
      return Promise.resolve({ id, version: row.version });
    }),
    remove: vi.fn((id: string) => {
      calls.push(`DELETE ${id}`);
      rows.delete(id);
      return Promise.resolve();
    }),
  };
}

const journal = () => useJournal.getState();

beforeEach(() => {
  journal().clear();
  clearRecordRefs();
  journal().setPathScope('/admin/catalog/rooms');
  Object.values(toast).forEach((fn) => fn.mockReset());
});

describe('спільна адреса запису в журналі', () => {
  it('створити, змінити, двічі скасувати й двічі повернути — правка йде на новий id', async () => {
    const server = fakeServer();
    const ops = { create: server.create, update: server.update, remove: server.remove };
    const created = await server.create({ name: '999' });
    trackSavedRecord<Body>({ kind: 'rooms', label: 'Додано аудиторію 999', before: null, after: { name: '999' }, saved: created, ...ops });
    const edited = await server.update(created.id, { name: '998', version: 0 });
    trackSavedRecord<Body>({ kind: 'rooms', label: 'Змінено аудиторію 998', before: { name: '999' }, after: { name: '998' }, saved: edited, ...ops });

    expect((await journal().undoLatest()).kind).toBe('ok');
    expect((await journal().undoLatest()).kind).toBe('ok');
    expect(server.rows.size).toBe(0);
    expect((await journal().redoLatest()).kind).toBe('ok');
    expect((await journal().redoLatest()).kind).toBe('ok');

    expect([...server.rows.values()]).toEqual([{ name: '998', version: 1 }]);
    expect(server.calls.slice(-2)).toEqual(['POST r2', 'PUT r2 v0']);
    expect(journal().entries.every((e) => e.status === 'done')).toBe(true);
  });

  it('правку запису, прибраного скасуванням створення, не надсилає й чесно пояснює', async () => {
    const server = fakeServer();
    const ops = { create: server.create, update: server.update, remove: server.remove };
    const created = await server.create({ name: '999' });
    const add = trackSavedRecord<Body>({ kind: 'rooms', label: 'Додано аудиторію 999', before: null, after: { name: '999' }, saved: created, ...ops });
    const edited = await server.update(created.id, { name: '998', version: 0 });
    trackSavedRecord<Body>({ kind: 'rooms', label: 'Змінено аудиторію 998', before: { name: '999' }, after: { name: '998' }, saved: edited, ...ops });

    await journal().undo(add.id); // з панелі — не за порядком
    const calls = server.calls.length;
    await journal().undoLatest();
    expect(server.calls.length).toBe(calls);
    expect(journal().entries[0]).toMatchObject({ status: 'stale', reason: expect.stringContaining('«Додано аудиторію 999»') });
  });

  it('чужа правка між діями — 409, а не перезапис', async () => {
    const server = fakeServer();
    const created = await server.create({ name: '305' });
    const edited = await server.update(created.id, { name: '306', version: 0 });
    trackSavedRecord<Body>({ kind: 'rooms', label: 'Змінено аудиторію 306', before: { name: '305' }, after: { name: '306' }, saved: edited, ...server });
    await server.update(created.id, { name: 'чуже', version: 1 });
    await journal().undoLatest();
    expect(journal().entries[0]?.status).toBe('stale');
    expect(server.rows.get(created.id)?.name).toBe('чуже');
  });
});

describe('збереження без змін', () => {
  it('однакові тіла (порядок полів і версія не важать)', () => {
    expect(sameRequest({ a: 1, b: null, version: 1 }, { b: null, a: 1, version: 2 })).toBe(true);
    expect(sameRequest({ a: 1 }, { a: 2 })).toBe(false);
  });

  it('закриває форму з короткою підказкою; нове — завжди зберігає', () => {
    const close = vi.fn();
    expect(skipUnchanged({ name: '13', version: 0 }, { name: '13', version: 0 }, close)).toBe(true);
    expect(close).toHaveBeenCalledOnce();
    expect(toast.info).toHaveBeenCalledWith('Змін немає — нічого не збережено', expect.anything());
    expect(skipUnchanged(null, { name: '13' }, close)).toBe(false);
    expect(skipUnchanged({ name: '13' }, { name: '14' }, close)).toBe(false);
  });
});
