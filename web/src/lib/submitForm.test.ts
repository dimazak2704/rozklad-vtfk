import { afterEach, describe, expect, it, vi } from 'vitest';
import { submitFocusedForm } from './submitForm';

afterEach(() => {
  document.body.innerHTML = '';
});

function mount(html: string): void {
  document.body.innerHTML = `<main id="main">${html}</main>`;
}

describe('Ctrl+S і Ctrl+Enter у панелі', () => {
  it('надсилає форму, де стоїть курсор', () => {
    mount('<form id="f"><textarea id="t"></textarea><button type="submit">Зберегти</button></form>');
    const form = document.getElementById('f') as HTMLFormElement;
    const submit = vi.fn((event: Event) => event.preventDefault());
    form.addEventListener('submit', submit);
    document.getElementById('t')?.focus();
    expect(submitFocusedForm('save')).toBe('submitted');
    expect(submit).toHaveBeenCalledOnce();
  });

  it('форма без змін (кнопки збереження нема) — «нічого», а не чужа форма сторінки', () => {
    mount('<form><textarea id="t"></textarea></form><form><input /><button type="submit">Інша</button></form>');
    document.getElementById('t')?.focus();
    expect(submitFocusedForm('save')).toBe('nothing');
  });

  it('вимкнена кнопка — «нічого», зайнята — «зберігається»', () => {
    mount('<form><input id="i" /><button type="submit" disabled>Зберегти</button></form>');
    document.getElementById('i')?.focus();
    expect(submitFocusedForm('save')).toBe('nothing');
    mount('<form><input id="i" /><button type="submit" aria-busy="true">Зберегти</button></form>');
    document.getElementById('i')?.focus();
    expect(submitFocusedForm('save')).toBe('busy');
  });

  it('редактор без <form> зберігає своєю кнопкою', () => {
    mount('<div data-save-region><input id="i" /><button data-save-action>Зберегти дзвінки</button></div>');
    const click = vi.fn();
    document.querySelector('[data-save-action]')?.addEventListener('click', click);
    document.getElementById('i')?.focus();
    expect(submitFocusedForm('save')).toBe('submitted');
    expect(click).toHaveBeenCalledOnce();
  });

  it('нічого зберігати — «немає»', () => {
    mount('<p>Текст</p>');
    expect(submitFocusedForm('save')).toBe('none');
  });
});
