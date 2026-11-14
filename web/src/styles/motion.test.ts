// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Читаємо файл як текст (див. tokens.test.ts): у jsdom-середовищі CSS-модулі порожні.
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as { readFileSync: (path: URL, encoding: 'utf8') => string };
const motionCss = readFileSync(new URL('./motion.css', import.meta.url), 'utf8');

describe('prefers-reduced-motion у motion.css', () => {
  /** Правила, що стоять поза блоком `@media (prefers-reduced-motion: no-preference)`. */
  const outside = (() => {
    const start = motionCss.indexOf('@media (prefers-reduced-motion: no-preference)');
    let depth = 0;
    let end = start;
    for (let i = motionCss.indexOf('{', start); i < motionCss.length; i += 1) {
      if (motionCss[i] === '{') depth += 1;
      if (motionCss[i] === '}') depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
    return motionCss.slice(0, start) + motionCss.slice(end + 1);
  })();

  it('сигнатурні рухи справді описані (у блоці для тих, хто рух не вимикав)', () => {
    expect(motionCss.length).toBeGreaterThan(outside.length);
    for (const name of ['digit-in', 'digit-out', 'strike-draw', 'live-pulse', 'word-in', 'reveal-in']) {
      expect(motionCss, name).toMatch(new RegExp(`animation:\\s*${name}\\b`));
    }
  });

  it('рух (зсув, закреслення, ореол, відлік, розгортка) — лише коли людина не просила його вимкнути', () => {
    for (const name of ['digit-in', 'digit-out', 'strike-draw', 'live-pulse', 'word-in', 'reveal-in', 'arrive-wash', 'pop-in', 'dialog-in']) {
      expect(outside, name).not.toMatch(new RegExp(`animation:\\s*${name}\\b`));
    }
  });

  it('відлік котиться на частку кегля, а стара цифра йде швидше й з ease-in (не зливається з новою)', () => {
    expect(motionCss).toMatch(/@keyframes digit-in\s*\{[^}]*translateY\(0\.12em\)/);
    expect(motionCss).toMatch(/@keyframes digit-out\s*\{[\s\S]*?translateY\(-0\.12em\)/);
    expect(motionCss).toMatch(/animation:\s*digit-out calc\(var\(--duration-base\) \* 0\.75\) var\(--ease-in\)/);
  });

  it('без руху цифра, що йде, не показується зовсім', () => {
    expect(outside).toMatch(/\.anim-digit-out\s*\{\s*display:\s*none;/);
  });
});

describe('вхід форми на сторінці входу (auth-page.css)', () => {
  const authCss = readFileSync(new URL('./auth-page.css', import.meta.url), 'utf8');
  const block = (() => {
    const at = authCss.indexOf('animation: auth-enter');
    return authCss.slice(authCss.lastIndexOf('@media', at), authCss.indexOf('@keyframes auth-enter'));
  })();

  it('лише для тих, хто рух не вимикав, без знака (він малює себе сам) і без повтору після помилки входу', () => {
    expect(block.startsWith('@media (prefers-reduced-motion: no-preference)')).toBe(true);
    expect(block).toMatch(/body\.auth:not\(:has\(\.auth-alert\)\) \.auth-column > :not\(\.auth-mark\)/);
    expect(authCss.match(/animation:\s*auth-enter\b/g)).toHaveLength(1);
  });

  it('рухає лише opacity і transform', () => {
    const keyframes = authCss.slice(authCss.indexOf('@keyframes auth-enter'));
    const body = keyframes.slice(0, keyframes.indexOf('}\n}') + 3);
    const props = [...body.matchAll(/^\s*([a-z-]+):/gm)].map((m) => m[1]);
    expect(props.sort()).toEqual(['opacity', 'transform']);
  });
});
