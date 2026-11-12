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
    for (const name of ['digit-in', 'digit-out', 'strike-draw', 'live-pulse', 'word-in', 'reveal-in', 'pop-in', 'dialog-in']) {
      expect(outside, name).not.toMatch(new RegExp(`animation:\\s*${name}\\b`));
    }
  });

  it('без руху цифра, що йде, не показується зовсім', () => {
    expect(outside).toMatch(/\.anim-digit-out\s*\{\s*display:\s*none;/);
  });
});
