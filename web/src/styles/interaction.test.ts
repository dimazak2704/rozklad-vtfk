// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Читаємо файл як текст (див. motion.test.ts): у jsdom-середовищі CSS-модулі порожні.
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as { readFileSync: (path: URL, encoding: 'utf8') => string };
const css = readFileSync(new URL('./interaction.css', import.meta.url), 'utf8');
const theme = readFileSync(new URL('./theme.css', import.meta.url), 'utf8');

/** Вміст блоку, що починається з `head` (з урахуванням вкладених дужок). */
function blocks(source: string, head: string): string[] {
  const found: string[] = [];
  let from = source.indexOf(head);
  while (from !== -1) {
    let depth = 0;
    const open = source.indexOf('{', from);
    for (let i = open; i < source.length; i += 1) {
      if (source[i] === '{') depth += 1;
      if (source[i] === '}') depth -= 1;
      if (depth === 0) {
        found.push(source.slice(open + 1, i));
        break;
      }
    }
    from = source.indexOf(head, open);
  }
  return found;
}

describe('interaction.css: риска наведення', () => {
  const hoverBlocks = blocks(css, '@media (hover: hover) and (pointer: fine)').join('\n');
  const outsideHover = (() => {
    let rest = css;
    for (const block of blocks(css, '@media (hover: hover) and (pointer: fine)')) rest = rest.replace(block, '');
    return rest;
  })();

  it('наведення діє лише з мишею: на дотику :hover не «залипає»', () => {
    expect(hoverBlocks).toMatch(/\.nav-link:hover::after/);
    expect(hoverBlocks).toMatch(/:hover \.u-line::after/);
    expect(outsideHover).not.toMatch(/:hover/);
  });

  it('рух — лише transform і лише для тих, хто не вимикав руху', () => {
    const transitions = css.match(/transition:[^;]+;/g) ?? [];
    expect(transitions.length).toBeGreaterThan(0);
    for (const rule of transitions) expect(rule).toMatch(/transition: (transform\b|none)/);
    const motionBlocks = [
      ...blocks(css, '@media (prefers-reduced-motion: no-preference)'),
      ...blocks(css, '@media (hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)'),
    ].join('\n');
    const animated = transitions.filter((rule) => !rule.includes('none'));
    for (const rule of animated) expect(motionBlocks).toContain(rule);
  });

  it('фокус з клавіатури показує ту саму риску одразу, без переходу', () => {
    expect(css).toMatch(/\.nav-link:focus-visible::after\s*\{\s*transform: scaleX\(1\);\s*transition: none;/);
    expect(css).toMatch(/:focus-visible \.u-line::after/);
  });

  it('активний розділ шапки — акцентна риска; наведення — нейтральна', () => {
    expect(css).toMatch(/\.nav-link\[aria-current='page'\]::after\s*\{[^}]*background-color: var\(--background-color-accent\)/);
    expect(css).toMatch(/\.nav-link::after\s*\{[^}]*background-color: var\(--border-color-strong\)/);
  });

  it('варіант hover-fine у темі вимагає миші (hover і pointer: fine)', () => {
    expect(theme).toMatch(/@custom-variant hover-fine\s*\{\s*@media \(hover: hover\) and \(pointer: fine\)/);
  });
});
