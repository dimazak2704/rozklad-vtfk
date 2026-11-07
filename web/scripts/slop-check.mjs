#!/usr/bin/env node
/**
 * Детектор «AI slop» (docs/UI.md §2): шукає в коді шаблонні прийоми, які власник заборонив,
 * і падає з кодом 1, якщо щось знайшов. Запускається в `npm run lint`.
 *
 * Виняток для конкретного рядка — коментар з причиною на тому ж або попередньому рядку:
 *   // slop-allow: <чому тут це доречно>        /* slop-allow: <чому> *\/
 *
 * Використання: node scripts/slop-check.mjs [шлях ...]   (за замовчуванням src та index.html)
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCANNED_EXT = new Set(['.ts', '.tsx', '.css', '.html']);
const SKIP_DIRS = new Set(['node_modules', 'dist', 'coverage', '.impeccable', 'playwright-report', 'test-results']);

/** Правило: що шукаємо, де, і як пояснити людині. */
const RULES = [
  {
    id: 'gradient',
    message: 'Декоративний градієнт. Світ «Розкладу» — пласкі кольорові поля без градієнтів (DESIGN.md).',
    ext: ['.ts', '.tsx', '.css', '.html'],
    test: (line) =>
      /(?<!repeating-)(linear|radial|conic)-gradient\(/.test(line) ||
      /\b(bg-gradient-to|bg-linear-to|bg-radial|bg-conic)-?[a-z]*/.test(line) ||
      /\b(from|via|to)-(purple|violet|indigo|fuchsia|pink|rose)-\d{2,3}\b/.test(line),
  },
  {
    id: 'purple-pink-gradient',
    message: 'Фіолетово-рожевий градієнт — головна прикмета шаблонного ШІ-дизайну (UI.md §2).',
    ext: ['.ts', '.tsx', '.css', '.html'],
    test: (line) => /gradient\([^)]*(purple|violet|indigo|fuchsia|magenta|pink|#[a-f0-9]{3,8})/i.test(line),
  },
  {
    id: 'gradient-text',
    message: 'Градієнтний текст. Акцент робиться вагою або розміром, не заливкою літер.',
    ext: ['.ts', '.tsx', '.css', '.html'],
    test: (line) => /\bbg-clip-text\b|background-clip:\s*text|-webkit-background-clip:\s*text|-webkit-text-fill-color:\s*transparent/.test(line),
  },
  {
    id: 'glassmorphism',
    message: 'Скло й розмиття тла як декор (glassmorphism).',
    ext: ['.ts', '.tsx', '.css', '.html'],
    test: (line) => /\bbackdrop-blur(-[a-z0-9]+)?\b|backdrop-filter:\s*blur/.test(line),
  },
  {
    id: 'neon-glow',
    message: 'Неонове світіння: тінь без зсуву з великим розмиттям. Глибина — лише для накладених шарів.',
    ext: ['.ts', '.tsx', '.css', '.html'],
    test: (line) =>
      /\b(drop-)?shadow-\[0_0_\d{2,}px/.test(line) || /box-shadow:\s*0(px)?\s+0(px)?\s+\d{2,}px/.test(line) || /text-shadow:\s*0(px)?\s+0(px)?\s+\d+px/.test(line),
  },
  {
    id: 'emoji-in-ui',
    message: 'Емодзі в інтерфейсі. Іконки — лише lucide (в Telegram-боті емодзі доречні, тут — ні).',
    ext: ['.tsx', '.html'],
    test: (line) => /(?![©®™])\p{Extended_Pictographic}/u.test(line),
  },
  {
    id: 'sparkles',
    message: 'Іконка «іскор» (✨ AI) як прикраса.',
    ext: ['.ts', '.tsx'],
    test: (line) => /\b(Sparkles|Sparkle|WandSparkles|Wand2|MagicWand)\b/.test(line),
  },
  {
    id: 'lorem',
    message: 'Lorem ipsum. Лише справжній зміст або чесний порожній стан.',
    ext: ['.ts', '.tsx', '.html', '.css'],
    test: (line) => /\blorem\b|\bipsum\b|dolor sit amet/i.test(line),
  },
  {
    id: 'empty-copy',
    message: 'Порожня шаблонна фраза («Welcome back», «Get started», «Ласкаво просимо»).',
    ext: ['.ts', '.tsx', '.html'],
    test: (line) =>
      /welcome back|get started|unlock the power|supercharge|seamless(ly)?\b|revolutioni[sz]e/i.test(line) ||
      /(?:^|[^\p{L}])(ласкаво просимо|з поверненням|почніть свою подорож|розблокуйте)/iu.test(line),
  },
  {
    id: 'fake-social-proof',
    message: 'Вигадані відгуки, рейтинги чи статистика («trusted by», «10x», «5 зірок»).',
    ext: ['.ts', '.tsx', '.html'],
    test: (line) => /trusted by|testimonial|\b\d+x faster\b|★★★|5 зірок|тисячі задоволених|99[.,]9\s?%/i.test(line),
  },
  {
    id: 'generic-font',
    message: 'Inter/Roboto як єдиний шрифт. Шрифти системи — Fira Sans і Fira Sans Condensed (DESIGN.md).',
    ext: ['.ts', '.tsx', '.css', '.html'],
    test: (line) =>
      /@fontsource(-variable)?\/(inter|roboto)\b/i.test(line) ||
      /font-family:\s*['"]?(Inter|Roboto)\b/.test(line) ||
      /--font-sans:\s*['"]?(Inter|Roboto)\b/.test(line) ||
      /font-\['?(Inter|Roboto)/.test(line),
  },
  {
    id: 'card-soup',
    message: 'Однакові картки rounded-2xl з тінню для всього. Структура — лініями й простором, тінь — лише шарам.',
    ext: ['.tsx'],
    test: (line) => /\brounded-(2xl|3xl)\b/.test(line) && /\bshadow-(sm|md|lg|xl|2xl)\b/.test(line),
  },
  {
    id: 'icon-tile',
    message: 'Іконка в кружечку як плитка-прикраса (icon-in-circle tiles).',
    ext: ['.tsx'],
    test: (line) =>
      /\brounded-(full|xl|2xl)\b/.test(line) &&
      /\b(size-(1[0-6]|20)|h-1[0-6] w-1[0-6])\b/.test(line) &&
      /\bjustify-center\b/.test(line) &&
      /\bbg-[a-z-]*(subtle|selected|accent)\b/.test(line),
  },
  {
    id: 'default-palette',
    message: 'Колір зі стандартної палітри Tailwind замість семантичного токена (bg-surface, text-secondary…).',
    ext: ['.ts', '.tsx', '.html'],
    test: (line) =>
      /\b(?:bg|text|border|ring|outline|fill|stroke|from|via|to|decoration|divide|shadow)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-(?:50|[1-9]00|950)\b/.test(
        line,
      ),
  },
  {
    id: 'raw-hex-in-component',
    message: 'Сирий hex-колір у компоненті. Кольори — лише токени з src/styles/theme.css.',
    ext: ['.tsx'],
    test: (line) => /(?:className|style)=.*#[0-9a-fA-F]{3,8}\b|\[(?:#[0-9a-fA-F]{3,8})\]/.test(line),
  },
  {
    id: 'eyebrow',
    message: 'Розріджений надпис капсом над заголовком (eyebrow/kicker). Заголовок має говорити сам.',
    ext: ['.tsx'],
    test: (line) => /\buppercase\b/.test(line) && /\btracking-(widest|\[0\.(1[5-9]|[2-9])\d*em\])/.test(line),
  },
];

function walk(target, out) {
  const stats = statSync(target);
  if (stats.isFile()) {
    if (SCANNED_EXT.has(extname(target))) out.push(target);
    return;
  }
  for (const entry of readdirSync(target)) {
    if (SKIP_DIRS.has(entry)) continue;
    walk(join(target, entry), out);
  }
}

/** Рядок лише з коментарем: правила стосуються коду й тексту інтерфейсу, не пояснень у коментарях. */
function isCommentOnly(line) {
  const trimmed = line.trim();
  return trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*') || trimmed.startsWith('{/*');
}

function isAllowed(lines, index) {
  const here = lines[index] ?? '';
  const before = lines[index - 1] ?? '';
  return /slop-allow:\s*\S/.test(here) || /slop-allow:\s*\S/.test(before);
}

/** Перевіряє текст файлу; повертає порушення. Експортовано для тестів. */
export function checkSource(text, ext) {
  const lines = text.split(/\r?\n/);
  const problems = [];
  lines.forEach((line, index) => {
    if (isCommentOnly(line)) return;
    for (const rule of RULES) {
      if (!rule.ext.includes(ext)) continue;
      if (!rule.test(line)) continue;
      if (isAllowed(lines, index)) continue;
      problems.push({ line: index + 1, rule: rule.id, message: rule.message, excerpt: line.trim().slice(0, 140) });
    }
  });
  return problems;
}

function main() {
  const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
  const targets = process.argv.slice(2);
  const roots = (targets.length > 0 ? targets : ['src', 'index.html']).map((t) => resolve(ROOT, t));
  const files = [];
  for (const root of roots) walk(root, files);

  let count = 0;
  for (const file of files) {
    const problems = checkSource(readFileSync(file, 'utf8'), extname(file));
    for (const problem of problems) {
      count += 1;
      console.error(`${relative(ROOT, file)}:${problem.line}  [${problem.rule}] ${problem.message}\n    ${problem.excerpt}`);
    }
  }

  if (count > 0) {
    console.error(`\nslop-check: знайдено ${count} шаблонних прийомів. Виправте або поясніть коментарем «slop-allow: причина».`);
    process.exit(1);
  }
  console.log(`slop-check: чисто (${files.length} файлів перевірено).`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
