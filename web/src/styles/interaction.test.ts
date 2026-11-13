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

  it('рух — лише transform (риска — clip-path) і лише для тих, хто не вимикав руху', () => {
    const transitions = css.match(/transition:[^;]+;/g) ?? [];
    expect(transitions.length).toBeGreaterThan(0);
    for (const rule of transitions) expect(rule).toMatch(/transition: (transform\b|clip-path\b|none)/);
    const motionBlocks = [
      ...blocks(css, '@media (prefers-reduced-motion: no-preference)'),
      ...blocks(css, '@media (hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)'),
    ].join('\n');
    const animated = transitions.filter((rule) => !rule.includes('none'));
    for (const rule of animated) expect(motionBlocks).toContain(rule);
    const animations = (css.match(/animation: [^;]+;/g) ?? []).filter((rule) => !rule.includes('none'));
    for (const rule of animations) expect(motionBlocks).toContain(rule);
  });

  it('риска не масштабується: однакова товщина під час руху й після (без scaleX і окремого шару)', () => {
    for (const selector of ['.u-line::after', '.nav-link::after']) {
      const rest = blocks(css, `  ${selector} {`)[0] ?? '';
      expect(rest).toMatch(/clip-path: inset\(-1px 0 -1px 100%\)/);
      expect(rest).not.toMatch(/transform|will-change/);
    }
    expect(css).not.toMatch(/scaleX\(/);
    // вхід зліва — анімацією; перехід на наведенні вимкнено, інакше риска росла б від правого краю
    expect(hoverBlocks).toMatch(/:hover \.u-line::after,[^{]*\{\s*transition: none;\s*animation: u-line-in/);
    expect(css).toMatch(/@keyframes u-line-in\s*\{\s*from\s*\{\s*clip-path: inset\(-1px 100% -1px 0\);/);
  });

  it('фокус з клавіатури показує ту саму риску одразу, без переходу', () => {
    expect(css).toMatch(/\.nav-link:focus-visible::after\s*\{\s*clip-path: inset\(-1px 0 -1px 0\);\s*transition: none;\s*animation: none;/);
    expect(css).toMatch(/:focus-visible \.u-line::after/);
  });

  it('активний розділ шапки — акцентна риска; наведення — нейтральна', () => {
    expect(css).toMatch(/\.nav-link\[aria-current='page'\]::after\s*\{[^}]*background-color: var\(--background-color-accent\)/);
    expect(css).toMatch(/\.nav-link::after\s*\{[^}]*background-color: var\(--border-color-strong\)/);
  });

  it('варіанти hover і hover-fine у темі вимагають миші (hover і pointer: fine)', () => {
    expect(theme).toMatch(/@custom-variant hover-fine\s*\{\s*@media \(hover: hover\) and \(pointer: fine\)/);
    // вбудований hover: перевизначено, тож старі класи hover: на телефоні теж не «залипають»
    expect(theme).toMatch(/@custom-variant hover\s*\{\s*@media \(hover: hover\) and \(pointer: fine\)/);
  });
});

describe('interaction.css: картка-«підгляд»', () => {
  it('картка без дій і обгортка Radix не ловлять мишу: курсор доходить до клітинки під карткою', () => {
    expect(css).toMatch(
      /\[data-radix-popper-content-wrapper\]:has\(> \[data-hover-peek\]\),\s*\[data-hover-peek\]\s*\{\s*pointer-events: none;/,
    );
  });
});
