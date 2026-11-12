/**
 * Ctrl/⌘+S і Ctrl/⌘+Enter у панелі диспетчера: зберегти форму, з якою людина зараз працює.
 *
 * Яка форма: та, де стоїть курсор; інакше — єдина форма у верхньому вікні; інакше — єдина форма
 * збереження на сторінці (Налаштування). Пошукові форми (`role="search"`) не рахуються.
 * Редактори без <form> позначають обгортку `data-save-region`, а кнопку — `data-save-action`.
 * Ctrl+Enter у вікні підтвердження без форми натискає його головну кнопку (останню в рядку кнопок):
 * так небезпечну дію не підтвердиш випадковим Enter, але й мишу брати не треба.
 *
 * Результат: `submitted` — надіслано; `busy` — уже зберігається; `nothing` — курсор у формі, але зберігати
 * нічого (кнопки збереження немає чи вона вимкнена, бо змін нема); `none` — форми не знайдено.
 */

export type SubmitOutcome = 'submitted' | 'busy' | 'nothing' | 'none';

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

/** Найближча обгортка редактора без <form>, де стоїть курсор. */
function ownRegion(): HTMLElement | null {
  return document.activeElement?.closest<HTMLElement>('[data-save-region]') ?? null;
}

/** Єдине, що можна зберегти у верхньому вікні чи на сторінці: форма або редактор без <form>. */
function soleTarget(modal: HTMLElement | null): HTMLFormElement | HTMLElement | null {
  const root: ParentNode | null = modal ?? document.getElementById('main');
  if (!root) return null;
  const forms = [...root.querySelectorAll('form')].filter(isSaveForm);
  const regions = [...root.querySelectorAll<HTMLElement>('[data-save-region]')];
  return forms.length + regions.length === 1 ? (forms[0] ?? regions[0] ?? null) : null;
}

export function findActiveForm(): HTMLFormElement | null {
  const own = ownForm(document.activeElement);
  if (own && isSaveForm(own)) return own;
  const sole = soleTarget(topModal());
  return sole instanceof HTMLFormElement ? sole : null;
}

function submitForm(form: HTMLFormElement): SubmitOutcome {
  const submitter = submitterOf(form);
  // кнопка вимкнена (зберігається чи нема змін) — клавішу поглинаємо, але вдруге не надсилаємо
  if (!submitter) return 'nothing';
  if (submitter.getAttribute('aria-busy') === 'true') return 'busy';
  if (submitter.disabled) return 'nothing';
  form.requestSubmit(submitter.form === form ? submitter : undefined);
  return 'submitted';
}

function clickSave(region: HTMLElement): SubmitOutcome {
  const action = region.querySelector<HTMLButtonElement>('[data-save-action]');
  if (!action) return 'nothing';
  if (action.getAttribute('aria-busy') === 'true') return 'busy';
  if (action.disabled) return 'nothing';
  action.click();
  return 'submitted';
}

export function submitFocusedForm(mode: 'save' | 'confirm'): SubmitOutcome {
  const modal = topModal();
  const own = ownForm(document.activeElement);
  if (own && isSaveForm(own)) return submitForm(own);
  // курсор у формі редагування без кнопки збереження (примітки без змін) — зберігати нічого,
  // і чужу форму сторінки за неї не надсилаємо
  if (own && own.getAttribute('role') !== 'search') return 'nothing';
  const region = ownRegion();
  if (region) return clickSave(region);
  const sole = soleTarget(modal);
  if (sole instanceof HTMLFormElement) return submitForm(sole);
  if (mode === 'confirm' && modal?.getAttribute('role') === 'alertdialog') {
    const buttons = [...modal.querySelectorAll<HTMLButtonElement>('button:not([disabled])')];
    const primary = buttons.at(-1);
    if (primary) {
      primary.click();
      return 'submitted';
    }
  }
  if (sole) return clickSave(sole);
  return 'none';
}
