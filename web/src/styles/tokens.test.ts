// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Читаємо файли як текст (див. auth/authPageCss.test.ts): типів node у src немає, тож fs беремо динамічно.
const fsModule = 'node:fs';
const { readFileSync, readdirSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
  readdirSync: (path: URL, options: { recursive: true }) => string[];
};
const srcRoot = new URL('../', import.meta.url);
const sources = readdirSync(srcRoot, { recursive: true })
  .map((file) => file.replaceAll('\\', '/'))
  .filter((file) => /\.(tsx?|css)$/.test(file) && !/\.test\.tsx?$/.test(file));
const read = (file: string) => readFileSync(new URL(file, srcRoot), 'utf8');

/**
 * Класи на кшталт `bg-[var(--grey-0)]` Tailwind пропускає мовчки: якщо змінної немає, елемент просто
 * лишається без кольору (так зникла ручка перемикача). Тест вимагає, щоб кожна var(--x) без запасного
 * значення була оголошена в стилях або в коді (style={{ '--x': … }}).
 */
describe('CSS-змінні', () => {
  const texts = sources.map((file) => ({ file, text: read(file) }));
  const defined = new Set(
    texts.flatMap(({ text }) => [...text.matchAll(/['"]?(--[\w-]+)['"]?\s*:/g)].map((match) => match[1] as string)),
  );

  it('кожна var(--x) у класах і стилях оголошена', () => {
    expect(sources.length).toBeGreaterThan(100);
    const missing = texts.flatMap(({ file, text }) =>
      [...text.matchAll(/var\(\s*(--[\w-]+)\s*\)/g)]
        .map((match) => match[1] as string)
        .filter((name) => !defined.has(name))
        .map((name) => `${file}: ${name}`),
    );
    expect([...new Set(missing)]).toEqual([]);
  });
});
