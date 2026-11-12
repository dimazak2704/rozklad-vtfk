import { fireEvent, render, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { helpGroups, useShortcutsStore } from '@/stores/shortcuts';
import { keyFromCode, matchesCombo, useHotkeys, type Hotkey } from './useHotkeys';

function press(init: KeyboardEventInit, target: EventTarget = window) {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(event);
  return event;
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('клавіші в українській розкладці', () => {
  it('фізична клавіша дає англійський символ', () => {
    expect(keyFromCode({ code: 'KeyN', shiftKey: false })).toBe('n');
    expect(keyFromCode({ code: 'Slash', shiftKey: false })).toBe('/');
    expect(keyFromCode({ code: 'Slash', shiftKey: true })).toBe('?');
    expect(keyFromCode({ code: 'BracketLeft', shiftKey: false })).toBe('[');
    expect(keyFromCode({ code: 'Digit3', shiftKey: false })).toBe('3');
    expect(keyFromCode({ code: 'Numpad7', shiftKey: false })).toBe('7');
    expect(keyFromCode({ code: 'Enter', shiftKey: false })).toBeNull();
  });

  it('«/» спрацьовує, хоча українська розкладка друкує на цій клавіші крапку', () => {
    const event = { key: '.', ctrlKey: false, metaKey: false, shiftKey: false, altKey: false };
    expect(matchesCombo(event, '/', keyFromCode({ code: 'Slash', shiftKey: false }))).toBe(true);
    // справжня крапка (клавіша Period) — не «/»
    expect(matchesCombo({ ...event, key: 'ю' }, '/', keyFromCode({ code: 'Period', shiftKey: false }))).toBe(false);
  });

  it('Ctrl+Я — це Ctrl+Z, «т» — це N', () => {
    expect(matchesCombo({ key: 'я', ctrlKey: true, metaKey: false, shiftKey: false, altKey: false }, 'mod+z', 'z')).toBe(true);
    expect(matchesCombo({ key: 'т', ctrlKey: false, metaKey: false, shiftKey: false, altKey: false }, 'n', 'n')).toBe(true);
    expect(matchesCombo({ key: 'я', ctrlKey: true, metaKey: false, shiftKey: true, altKey: false }, 'mod+z', 'z')).toBe(false);
  });

  it('«?» — і через Shift+/ в англійській, і через Shift+7 в українській', () => {
    expect(matchesCombo({ key: '?', ctrlKey: false, metaKey: false, shiftKey: true, altKey: false }, '?', '?')).toBe(true);
    expect(matchesCombo({ key: '?', ctrlKey: false, metaKey: false, shiftKey: true, altKey: false }, '?', '&')).toBe(true);
    expect(matchesCombo({ key: ',', ctrlKey: false, metaKey: false, shiftKey: true, altKey: false }, '?', '?')).toBe(true);
  });
});

describe('хто отримує клавішу', () => {
  it('сторінковий «/» перемагає запасну палітру, навіть якщо палітра підписалася пізніше', () => {
    const page = vi.fn();
    const palette = vi.fn();
    const pageHook = renderHook(() => useHotkeys([{ keys: '/', handler: page, description: 'Пошук у таблиці', group: 'Таблиця' }]));
    const paletteHook = renderHook(() => useHotkeys([{ keys: '/', handler: palette, description: 'Знайти розклад', group: 'Загальні', priority: -1 }]));
    const event = press({ key: '/', code: 'Slash' });
    expect(page).toHaveBeenCalledOnce();
    expect(palette).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(true);
    // довідка показує «/» один раз — те, що він робить тут
    const groups = helpGroups(useShortcutsStore.getState().entries);
    const slash = groups.flatMap(([, items]) => items).filter((item) => item.keys === '/');
    expect(slash).toEqual([{ keys: '/', description: 'Пошук у таблиці' }]);
    pageHook.unmount();
    press({ key: '/', code: 'Slash' });
    expect(palette).toHaveBeenCalledOnce();
    paletteHook.unmount();
  });

  it('за рівного пріоритету — пізніше змонтована частина', () => {
    const shell = vi.fn();
    const page = vi.fn();
    const a = renderHook(() => useHotkeys([{ keys: 'n', handler: shell }]));
    const b = renderHook(() => useHotkeys([{ keys: 'n', handler: page }]));
    press({ key: 'n', code: 'KeyN' });
    expect(page).toHaveBeenCalledOnce();
    expect(shell).not.toHaveBeenCalled();
    a.unmount();
    b.unmount();
  });

  it('обробник, що повернув false, віддає клавішу далі й браузеру', () => {
    const fallback = vi.fn();
    const a = renderHook(() => useHotkeys([{ keys: 'enter', handler: fallback }]));
    const b = renderHook(() => useHotkeys([{ keys: 'enter', handler: () => false }]));
    const event = press({ key: 'Enter', code: 'Enter' });
    expect(fallback).toHaveBeenCalledOnce();
    expect(event.defaultPrevented).toBe(true);
    a.unmount();
    const passed = press({ key: 'Enter', code: 'Enter' });
    expect(passed.defaultPrevented).toBe(false);
    b.unmount();
  });

  it('не спрацьовує під час набору тексту, крім allowInInputs', () => {
    const plain = vi.fn();
    const always = vi.fn();
    const hotkeys: Hotkey[] = [
      { keys: 'n', handler: plain },
      { keys: 'mod+k', handler: always, allowInInputs: true },
    ];
    const hook = renderHook(() => useHotkeys(hotkeys));
    const { getByRole } = render(<input aria-label="Поле" />);
    const input = getByRole('textbox');
    fireEvent.keyDown(input, { key: 'n', code: 'KeyN' });
    fireEvent.keyDown(input, { key: 'k', code: 'KeyK', ctrlKey: true });
    expect(plain).not.toHaveBeenCalled();
    expect(always).toHaveBeenCalledOnce();
    hook.unmount();
  });

  it('мовчить поверх модального вікна, крім allowInModal', () => {
    const page = vi.fn();
    const journal = vi.fn();
    const hook = renderHook(() =>
      useHotkeys([
        { keys: 'n', handler: page },
        { keys: 'mod+shift+h', handler: journal, allowInModal: true },
      ]),
    );
    render(<div role="dialog" aria-modal="true" />);
    press({ key: 'n', code: 'KeyN' });
    press({ key: 'H', code: 'KeyH', ctrlKey: true, shiftKey: true });
    expect(page).not.toHaveBeenCalled();
    expect(journal).toHaveBeenCalledOnce();
    hook.unmount();
  });
});

describe('довідка «?»', () => {
  it('спершу розділи сторінки, потім загальні; підказки без обробника не витісняють клавіш', () => {
    const groups = helpGroups({
      a: { keys: 'mod+k', description: 'Пошук і команди', group: 'Загальні', order: 1 },
      b: { keys: 'n', description: 'Нова аудиторія', group: 'Аудиторії', order: 5 },
      c: { keys: 'mod+z', description: 'Скасувати', group: 'Дії', order: 2 },
      d: { keys: 'enter', description: 'Зберегти форму', group: 'Форми й вікна', priority: -2 },
      e: { keys: 'enter', description: 'Відкрити рядок', group: 'Аудиторії', order: 5 },
    });
    expect(groups.map(([name]) => name)).toEqual(['Аудиторії', 'Дії', 'Форми й вікна', 'Загальні']);
    expect(groups[0]?.[1]).toEqual([
      { keys: 'n', description: 'Нова аудиторія' },
      { keys: 'enter', description: 'Відкрити рядок' },
    ]);
  });
});
