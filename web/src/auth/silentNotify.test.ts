import { UserManager } from 'oidc-client-ts';
import { describe, expect, it, vi } from 'vitest';
import { notifyParent } from './silentNotify';

const CALLBACK = 'http://localhost:3000/login/silent.html?code=abc&state=xyz';

describe('повідомлення з iframe тихого оновлення', () => {
  it('таке саме, як надсилає signinSilentCallback() бібліотеки oidc-client-ts', async () => {
    const fromLibrary = vi.spyOn(window, 'postMessage').mockImplementation(() => undefined);
    const manager = new UserManager({ authority: 'http://localhost:3000/auth', client_id: 'rozklad-web', redirect_uri: CALLBACK });
    await manager.signinSilentCallback(CALLBACK);
    expect(fromLibrary).toHaveBeenCalledOnce();
    const [libraryMessage, libraryOrigin] = fromLibrary.mock.calls[0] ?? [];

    const postMessage = vi.fn();
    notifyParent({ parent: { postMessage } as unknown as Window, location: new URL(CALLBACK) as unknown as Location });
    expect(postMessage).toHaveBeenCalledWith(libraryMessage, 'http://localhost:3000');
    expect(libraryOrigin).toBe(window.location.origin);
  });

  it('відкрита не в iframe сторінка нічого не надсилає', () => {
    const postMessage = vi.fn();
    const win = { location: new URL(CALLBACK) as unknown as Location } as { parent: Window; location: Location };
    win.parent = win as unknown as Window;
    Object.assign(win, { postMessage });
    notifyParent(win);
    expect(postMessage).not.toHaveBeenCalled();
  });
});
