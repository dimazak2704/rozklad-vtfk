import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { classifyUndoError, GONE_REASON, STALE_REASON } from './errors';
import { JOURNAL_LIMIT, latestRedoable, latestUndoable, RELOADED_REASON, restoreEntries, useJournal } from './journal';

const journal = () => useJournal.getState();

beforeEach(() => {
  journal().clear();
  journal().setScopeOverride(null);
  journal().setPathScope('/admin/catalog/rooms');
});

describe('журнал дій', () => {
  it('запис з undo — «виконано», без undo — незворотний з поясненням', () => {
    const done = journal().record({ label: 'Додано аудиторію 305', undo: vi.fn() });
    const final = journal().record({ label: 'Опубліковано розпорядження №35' });
    expect(done).toMatchObject({ status: 'done', canUndo: true, canRedo: false, scope: '/admin/catalog/rooms' });
    expect(final).toMatchObject({ status: 'final', canUndo: false, reason: 'Цю дію не можна скасувати' });
    // найновіші — першими
    expect(journal().entries.map((e) => e.label)).toEqual(['Опубліковано розпорядження №35', 'Додано аудиторію 305']);
  });

  it('Ctrl+Z скасовує останню дію сторінки, оминаючи незворотні; повтор повертає її', async () => {
    const undoFirst = vi.fn().mockResolvedValue(undefined);
    const undoSecond = vi.fn().mockResolvedValue(undefined);
    const redoSecond = vi.fn().mockResolvedValue(undefined);
    journal().record({ label: 'перша', undo: undoFirst });
    journal().record({ label: 'друга', undo: undoSecond, redo: redoSecond });
    journal().record({ label: 'незворотна' });

    const outcome = await journal().undoLatest();
    expect(outcome.kind).toBe('ok');
    expect(undoSecond).toHaveBeenCalledOnce();
    expect(undoFirst).not.toHaveBeenCalled();
    expect(journal().entries.find((e) => e.label === 'друга')?.status).toBe('undone');

    expect(latestRedoable(journal(), '/admin/catalog/rooms')?.label).toBe('друга');
    expect((await journal().redoLatest()).kind).toBe('ok');
    expect(redoSecond).toHaveBeenCalledOnce();
    expect(journal().entries.find((e) => e.label === 'друга')?.status).toBe('done');
  });

  it('нова дія обриває гілку «повернути»', async () => {
    journal().record({ label: 'a', undo: vi.fn().mockResolvedValue(undefined), redo: vi.fn() });
    await journal().undoLatest();
    expect(latestRedoable(journal(), '/admin/catalog/rooms')).toBeDefined();
    journal().record({ label: 'b', undo: vi.fn() });
    expect(latestRedoable(journal(), '/admin/catalog/rooms')).toBeUndefined();
    expect((await journal().redoLatest()).kind).toBe('empty');
  });

  it('Ctrl+Z діє лише на дії поточної сторінки й каже, що інші є в журналі', async () => {
    const undo = vi.fn().mockResolvedValue(undefined);
    journal().record({ label: 'на іншій сторінці', undo, scope: '/admin/load' });
    const outcome = await journal().undoLatest();
    expect(outcome).toEqual({ kind: 'empty', elsewhere: true });
    expect(undo).not.toHaveBeenCalled();
  });

  it('сторінка може уточнити свою частину (версію розкладу)', () => {
    journal().setScopeOverride('timetable:v1');
    expect(journal().record({ label: 'x', undo: vi.fn() }).scope).toBe('timetable:v1');
    journal().setPathScope('/admin/timetable');
    expect(journal().activeScope).toBe('timetable:v1');
    journal().setScopeOverride(null);
    expect(journal().activeScope).toBe('/admin/timetable');
  });

  it('збій мережі — «не скасовано», можна спробувати ще раз', async () => {
    const undo = vi
      .fn()
      .mockRejectedValueOnce(new ApiError({ status: 0, kind: 'network' }))
      .mockResolvedValueOnce(undefined);
    const entry = journal().record({ label: 'Змінено аудиторію 305', undo });
    const first = await journal().undo(entry.id);
    expect(first.kind).toBe('error');
    expect(journal().entries[0]).toMatchObject({ status: 'failed', canUndo: true });
    expect(latestUndoable(journal().entries, '/admin/catalog/rooms')?.id).toBe(entry.id);
    expect((await journal().undo(entry.id)).kind).toBe('ok');
    expect(journal().entries[0]?.status).toBe('undone');
  });

  it('409 — хтось змінив запис після вас: «застаріло», без повторних спроб', async () => {
    const undo = vi.fn().mockRejectedValue(new ApiError({ status: 409, type: 'urn:rozklad:problem:stale-data' }));
    const entry = journal().record({ label: 'Змінено аудиторію 305', undo });
    await journal().undo(entry.id);
    expect(journal().entries[0]).toMatchObject({ status: 'stale', reason: STALE_REASON });
    expect(latestUndoable(journal().entries, '/admin/catalog/rooms')).toBeUndefined();
    expect((await journal().undo(entry.id)).kind).toBe('empty');
    expect(undo).toHaveBeenCalledOnce();
  });

  it('422 — правило не дає повернути (заміну вже внесено в розпорядження)', async () => {
    const undo = vi.fn().mockRejectedValue(new ApiError({ status: 422, detail: 'Заміну вже внесено в опубліковане розпорядження.' }));
    const entry = journal().record({ label: 'Призначено Коваль — 4КІ1, 2 пара', undo });
    await journal().undo(entry.id);
    expect(journal().entries[0]).toMatchObject({ status: 'final', reason: 'Заміну вже внесено в опубліковане розпорядження.' });
  });

  it('невдалий повтор лишає дію скасованою', async () => {
    const entry = journal().record({
      label: 'a',
      undo: vi.fn().mockResolvedValue(undefined),
      redo: vi.fn().mockRejectedValue(new ApiError({ status: 503 })),
    });
    await journal().undo(entry.id);
    const outcome = await journal().redo(entry.id);
    expect(outcome.kind).toBe('error');
    expect(journal().entries[0]?.status).toBe('undone');
    expect(latestRedoable(journal(), '/admin/catalog/rooms')?.id).toBe(entry.id);
  });

  it('запамʼятовує, що не вдалося — скасування чи повтор', async () => {
    const entry = journal().record({
      label: 'a',
      undo: vi.fn().mockResolvedValue(undefined),
      redo: vi.fn().mockRejectedValue(new ApiError({ status: 404 })),
    });
    await journal().undo(entry.id);
    await journal().redo(entry.id);
    expect(journal().entries[0]).toMatchObject({ status: 'stale', failedOn: 'redo' });
  });

  it('поки одна дія скасовується, друга чекає', async () => {
    let release = () => {};
    const slow = vi.fn(() => new Promise<void>((resolve) => (release = resolve)));
    const a = journal().record({ label: 'a', undo: slow });
    const b = journal().record({ label: 'b', undo: vi.fn() });
    const running = journal().undo(a.id);
    expect(journal().entries.find((e) => e.id === a.id)?.status).toBe('pending');
    expect(await journal().undo(b.id)).toEqual({ kind: 'busy' });
    release();
    expect((await running).kind).toBe('ok');
  });

  it(`тримає не більше ${JOURNAL_LIMIT} записів`, () => {
    for (let i = 0; i < JOURNAL_LIMIT + 5; i += 1) journal().record({ label: `#${i}`, undo: vi.fn() });
    expect(journal().entries).toHaveLength(JOURNAL_LIMIT);
    expect(journal().entries.at(-1)?.label).toBe('#5');
  });
});

describe('після перезавантаження й зміни користувача', () => {
  it('записи лишаються для читання, але скасувати їх звідси вже не можна', () => {
    const done = journal().record({ label: 'Додано аудиторію 305', undo: vi.fn(), redo: vi.fn() });
    const final = journal().record({ label: 'Опубліковано розпорядження №35' });
    const restored = restoreEntries([done, final, { ...done, id: 'u', status: 'undone' }]);
    expect(restored[0]).toMatchObject({ status: 'final', canUndo: false, reason: RELOADED_REASON });
    expect(restored[1]).toMatchObject({ status: 'final', reason: 'Цю дію не можна скасувати' });
    expect(restored[2]).toMatchObject({ status: 'undone', canRedo: false });
  });

  it('записи зберігаються у сховищі вкладки', () => {
    journal().record({ label: 'Додано аудиторію 305', undo: vi.fn() });
    expect(window.sessionStorage.getItem('rozklad.journal')).toContain('Додано аудиторію 305');
  });

  it('інший користувач у цій вкладці починає з порожнього журналу', () => {
    journal().setOwner('u1');
    journal().record({ label: 'a', undo: vi.fn() });
    journal().setOwner('u1');
    expect(journal().entries).toHaveLength(1);
    journal().setOwner('u2');
    expect(journal().entries).toHaveLength(0);
  });
});

describe('чому не скасовано', () => {
  it('розрізняє застарілі дані, зниклий запис, правило і збій', () => {
    expect(classifyUndoError(new ApiError({ status: 409, type: 'urn:rozklad:problem:stale-data' }))).toEqual({ status: 'stale', reason: STALE_REASON });
    expect(classifyUndoError(new ApiError({ status: 409, detail: 'Аудиторія вже є в розкладі.' })).status).toBe('stale');
    expect(classifyUndoError(new ApiError({ status: 404 }))).toEqual({ status: 'stale', reason: GONE_REASON });
    expect(classifyUndoError(new ApiError({ status: 422, detail: 'Номер уже використано.' }))).toEqual({ status: 'final', reason: 'Номер уже використано.' });
    expect(classifyUndoError(new ApiError({ status: 500 })).status).toBe('failed');
    expect(classifyUndoError(new Error('boom'))).toEqual({ status: 'failed', reason: 'boom' });
  });
});
