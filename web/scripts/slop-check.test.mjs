// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { checkSource } from './slop-check.mjs';

const rules = (text, ext = '.tsx') => checkSource(text, ext).map((problem) => problem.rule);

describe('slop-check', () => {
  it('ловить фіолетово-рожевий градієнт і градієнтний текст', () => {
    expect(rules('<h1 className="bg-gradient-to-r from-purple-500 to-pink-500 bg-clip-text text-transparent">')).toEqual(
      expect.arrayContaining(['gradient', 'gradient-text', 'default-palette']),
    );
    expect(rules('.hero { background: linear-gradient(90deg, #7c3aed, #ec4899); }', '.css')).toEqual(
      expect.arrayContaining(['gradient', 'purple-pink-gradient']),
    );
  });

  it('пропускає нейтральне штрихування repeating-linear-gradient', () => {
    expect(rules('background-image: repeating-linear-gradient(135deg, var(--x) 0 1px, transparent 1px 7px);', '.css')).toEqual([]);
  });

  it('ловить емодзі в інтерфейсі, але не типографські символи', () => {
    expect(rules('<span>✨ Новий розклад</span>')).toContain('emoji-in-ui');
    expect(rules('<span>Пн · 08:30–09:30 → ауд. 6 ©</span>')).toEqual([]);
  });

  it('ловить порожні шаблонні фрази й lorem', () => {
    expect(rules("<p>Welcome back!</p>")).toContain('empty-copy');
    expect(rules('<p>Ласкаво просимо до системи</p>')).toContain('empty-copy');
    expect(rules('<p>Lorem ipsum dolor sit amet</p>')).toContain('lorem');
  });

  it('ловить скло, неон, картки з тінню та eyebrow', () => {
    expect(rules('<div className="backdrop-blur-md bg-white/10">')).toContain('glassmorphism');
    expect(rules('<div className="shadow-[0_0_40px_#a855f7]">')).toEqual(expect.arrayContaining(['neon-glow', 'raw-hex-in-component']));
    expect(rules('<div className="rounded-2xl shadow-lg p-6">')).toContain('card-soup');
    expect(rules('<p className="text-meta uppercase tracking-widest">Можливості</p>')).toContain('eyebrow');
  });

  it('не чіпає коментарі та рядки з поясненим винятком', () => {
    expect(rules('// приклад: bg-gradient-to-r from-purple-500')).toEqual([]);
    expect(rules('// slop-allow: прев’ю чужого макета\n<div className="backdrop-blur-md">')).toEqual([]);
  });

  it('вимагає токени замість стандартної палітри Tailwind та Inter', () => {
    expect(rules('<p className="text-gray-400">')).toContain('default-palette');
    expect(rules("import '@fontsource/inter';", '.ts')).toContain('generic-font');
  });
});
