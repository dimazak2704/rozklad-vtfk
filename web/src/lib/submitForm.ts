/**
 * Ctrl/⌘+S і Ctrl/⌘+Enter у панелі диспетчера: зберегти форму, з якою людина зараз працює.
 *
 * Яка форма: та, де стоїть курсор; інакше — єдина форма у верхньому вікні; інакше — єдина форма
 * збереження на сторінці (Налаштування). Пошукові форми (`role="search"`) не рахуються.
 * Ctrl+Enter у вікні підтвердження без форми натискає його головну кнопку (останню в рядку кнопок):
 * так небезпечну дію не підтвердиш випадковим Enter, але й мишу брати не треба.
 *
 * Повертає `false`, якщо зберігати нічого — тоді клавіша лишається браузеру.
 */

const MODAL = '[role="dialog"][aria-modal="true"],[role="alertdialog"]';

function topModal(): HTMLElement | null {
  const modals = document.querySelectorAll<HTMLElement>(MODAL);
  return modals.item(modals.length - 1);
}

function isSaveForm(form: HTMLFormElement): boolean {
  return form.getAttribute('role') !== 'search' && submitterOf(form) !== null;
}

/** Кнопка збереження форми: всередині або повʼязана атрибутом `form` (кнопка в підвалі вікна). */
function submitterOf(form: HTMLFormElement): HTMLButtonElement | HTMLInputElement | null {
  const inside = form.querySelector<HTMLButtonElement | HTMLInputElement>('button[type="submit"], input[type="submit"]');
  if (inside) return inside;
  return form.id ? document.querySelector<HTMLButtonElement>(`button[type="submit"][form="${CSS.escape(form.id)}"]`) : null;
}

function ownForm(element: Element | null): HTMLFormElement | null {
  if (!element) return null;
  if ('form' in element && element.form instanceof HTMLFormElement) return element.form;
  return element.closest('form');
}

export function findActiveForm(): HTMLFormElement | null {
  const own = ownForm(document.activeElement);
  if (own && isSaveForm(own)) return own;
  const modal = topModal();
  const root: ParentNode | null = modal ?? document.getElementById('main');
  if (!root) return null;
  const forms = [...root.querySelectorAll('form')].filter(isSaveForm);
  return forms.length === 1 ? (forms[0] ?? null) : null;
}

export function submitFocusedForm(mode: 'save' | 'confirm'): boolean {
  const modal = topModal();
  const form = findActiveForm();
  if (form) {
    const submitter = submitterOf(form);
    // кнопка вимкнена (зберігається чи нема змін) — клавішу поглинаємо, але вдруге не надсилаємо
    if (submitter && !submitter.disabled && submitter.getAttribute('aria-busy') !== 'true') {
      form.requestSubmit(submitter.form === form ? submitter : undefined);
    }
    return true;
  }
  if (mode === 'confirm' && modal?.getAttribute('role') === 'alertdialog') {
    const buttons = [...modal.querySelectorAll<HTMLButtonElement>('button:not([disabled])')];
    const primary = buttons.at(-1);
    if (primary) {
      primary.click();
      return true;
    }
  }
  return false;
}
