import { afterEach, describe, expect, it, vi } from 'vitest';
import { APP_STYLES_ATTR, APP_STYLES_TIMEOUT_MS, applyAppStyles, deferAppStyles } from './app-styles';

const BUILT = `<head>
    <link rel="stylesheet" crossorigin href="/assets/index-CnlIwFVg.css">
  </head>`;

describe('deferAppStyles (збірка index.html)', () => {
  it('стилі застосунку вантажаться першочергово, але не блокують перше малювання', () => {
    const html = deferAppStyles(BUILT);

    expect(html).toContain('<link rel="preload" as="style" crossorigin href="/assets/index-CnlIwFVg.css">');
    expect(html).toContain(`<link rel="stylesheet" crossorigin href="/assets/index-CnlIwFVg.css" media="print" ${APP_STYLES_ATTR}>`);
    expect(html).not.toContain('<link rel="stylesheet" crossorigin href="/assets/index-CnlIwFVg.css">');
  });

  it('немає посилання на стилі — збірка падає, а не мовчки лишає сторінку без них', () => {
    expect(() => deferAppStyles('<head></head>')).toThrow(/стилі застосунку/);
  });
});

function styleLink(loaded: boolean): HTMLLinkElement {
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.media = 'print';
  link.setAttribute(APP_STYLES_ATTR, '');
  if (loaded) Object.defineProperty(link, 'sheet', { configurable: true, value: {} });
  document.head.append(link);
  return link;
}

afterEach(() => {
  document.head.querySelectorAll(`link[${APP_STYLES_ATTR}]`).forEach((link) => link.remove());
  vi.useRealTimers();
});

describe('applyAppStyles (старт застосунку)', () => {
  it('стилі вже завантажені — вмикає їх одразу', async () => {
    const link = styleLink(true);

    await applyAppStyles();

    expect(link.media).toBe('all');
  });

  it('стилі ще вантажаться — вмикає і чекає завантаження', async () => {
    const link = styleLink(false);
    let ready = false;
    const waiting = applyAppStyles().then(() => {
      ready = true;
    });

    expect(link.media).toBe('all');
    await Promise.resolve();
    expect(ready).toBe(false);

    link.dispatchEvent(new Event('load'));
    await waiting;
    expect(ready).toBe(true);
  });

  it('стилі не завантажились — однаково рендеримо', async () => {
    const link = styleLink(false);
    const waiting = applyAppStyles();

    link.dispatchEvent(new Event('error'));

    await expect(waiting).resolves.toBeUndefined();
  });

  it('подія вже минула — не чекає вічно', async () => {
    vi.useFakeTimers();
    styleLink(false);
    const waiting = applyAppStyles();

    await vi.advanceTimersByTimeAsync(APP_STYLES_TIMEOUT_MS);

    await expect(waiting).resolves.toBeUndefined();
  });

  it('у dev посилання немає (стилі вставляє Vite) — нічого не чекає', async () => {
    await expect(applyAppStyles()).resolves.toBeUndefined();
  });
});
