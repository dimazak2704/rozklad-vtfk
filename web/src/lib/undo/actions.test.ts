import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { lowerFirst, redoLatest, track, trackFinal, undoLatest } from './actions';
import { useJournal } from './journal';

const toast = vi.hoisted(() => ({
  undo: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}));
vi.mock('@/components/ui/toast', () => ({ notify: toast }));

beforeEach(() => {
  useJournal.getState().clear();
  useJournal.getState().setPathScope('/admin/catalog/rooms');
  Object.values(toast).forEach((fn) => fn.mockReset());
});

describe('дії журналу з тостами', () => {
  it('дія з undo показує тост «Скасувати»; Ctrl+Z — «Скасовано: …» з «Повернути»', async () => {
    const undo = vi.fn().mockResolvedValue(undefined);
    const redo = vi.fn().mockResolvedValue(undefined);
    const entry = track({ label: 'Додано аудиторію 305', undo, redo, note: 'Нотатка' });
    expect(toast.undo).toHaveBeenCalledWith('Додано аудиторію 305', expect.objectContaining({ id: entry.id, description: 'Нотатка' }));

    await undoLatest();
    expect(undo).toHaveBeenCalledOnce();
    expect(toast.undo).toHaveBeenLastCalledWith('Скасовано: додано аудиторію 305', expect.objectContaining({ id: entry.id, undoLabel: 'Повернути' }));

    // «Повернути» в тості — повтор
    const options = toast.undo.mock.lastCall?.[1] as { onUndo: () => void };
    options.onUndo();
    await vi.waitFor(() => expect(redo).toHaveBeenCalledOnce());
    await vi.waitFor(() => expect(toast.undo).toHaveBeenLastCalledWith('Повернуто: додано аудиторію 305', expect.anything()));
  });

  it('без повтору після скасування — просто інформація', async () => {
    track({ label: 'Призначено Коваль — 4КІ1, 2 пара', undo: vi.fn().mockResolvedValue(undefined) });
    await undoLatest();
    expect(toast.info).toHaveBeenCalledWith('Скасовано: призначено Коваль — 4КІ1, 2 пара', expect.anything());
    await redoLatest();
    expect(toast.info).toHaveBeenLastCalledWith('Повертати нічого', expect.anything());
  });

  it('конфлікт — попередження з поясненням, а не тихий перезапис', async () => {
    track({ label: 'Змінено аудиторію 305', undo: vi.fn().mockRejectedValue(new ApiError({ status: 409, type: 'urn:rozklad:problem:stale-data' })) });
    await undoLatest();
    expect(toast.warning).toHaveBeenCalledWith(
      'Не скасовано: змінено аудиторію 305',
      expect.objectContaining({ description: expect.stringContaining('Хтось змінив') }),
    );
  });

  it('незворотна дія — лише запис у журналі, без тосту', () => {
    const entry = trackFinal('Опубліковано розпорядження №35', 'Потрібне доповнення');
    expect(entry).toMatchObject({ status: 'final', reason: 'Потрібне доповнення' });
    expect(toast.undo).not.toHaveBeenCalled();
  });

  it('нема чого скасувати тут, але є деінде — пропонує журнал', async () => {
    track({ label: 'x', undo: vi.fn(), scope: '/admin/load', silent: true });
    await undoLatest();
    expect(toast.undo).toHaveBeenCalledWith('На цій сторінці скасовувати нічого', expect.objectContaining({ undoLabel: 'Журнал дій' }));
  });

  it('малу літеру ставить лише на початку звичайного слова', () => {
    expect(lowerFirst('Додано аудиторію')).toBe('додано аудиторію');
    expect(lowerFirst('ЕУ · 4КІ1')).toBe('ЕУ · 4КІ1');
    expect(lowerFirst('')).toBe('');
  });
});
