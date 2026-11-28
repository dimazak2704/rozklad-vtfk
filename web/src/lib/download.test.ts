import { afterEach, describe, expect, it, vi } from 'vitest';
import { saveBlob } from './download';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('saveBlob', () => {
  it('клікає тимчасове посилання з назвою файлу, прибирає його й згодом звільняє памʼять', () => {
    vi.useFakeTimers();
    const create = vi.fn(() => 'blob:file');
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    saveBlob(new Blob(['x']), 'Розпорядження №36.pdf');

    expect(click).toHaveBeenCalledTimes(1);
    const clicked = click.mock.contexts[0] as HTMLAnchorElement;
    expect(clicked.download).toBe('Розпорядження №36.pdf');
    expect(clicked.rel).toBe('noopener');
    expect(clicked.isConnected).toBe(false);
    expect(revoke).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(revoke).toHaveBeenCalledWith('blob:file');
  });
});
