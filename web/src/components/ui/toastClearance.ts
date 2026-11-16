/**
 * Сповіщення (sonner) стоять унизу праворуч — там само, де липкі панелі «Є незбережені зміни» з кнопками
 * «Зберегти» і «Скасувати». Сповіщення про помилку накривало ці кнопки, а наведення на нього ще й
 * зупиняло його таймер, тож після помилки зберегти вдруге було нічим.
 *
 * Панель реєструється як «перешкода» через callback-ref `toastClearanceRef`; поки вона на сторінці,
 * змінна `--toast-clearance` на `<html>` дорівнює її висоті з проміжком, і `Toaster` піднімає
 * сповіщення над нею. Прихована панель (`display: none`, висота 0) місця не займає.
 */

export const TOAST_CLEARANCE_VAR = '--toast-clearance';

/** Проміжок між панеллю і сповіщенням, px. */
const GAP = 8;

const bars = new Set<HTMLElement>();
let observer: ResizeObserver | null = null;

function update(): void {
  let clearance = 0;
  for (const bar of bars) {
    const height = bar.offsetHeight;
    if (height > 0) clearance = Math.max(clearance, height + GAP);
  }
  const root = document.documentElement.style;
  if (clearance > 0) root.setProperty(TOAST_CLEARANCE_VAR, `${clearance}px`);
  else root.removeProperty(TOAST_CLEARANCE_VAR);
}

/** Callback-ref для липкої панелі дій унизу сторінки: сповіщення стануть над нею, доки вона на сторінці. */
export function toastClearanceRef(element: HTMLElement | null): (() => void) | undefined {
  if (!element || typeof document === 'undefined') return undefined;
  bars.add(element);
  if (typeof ResizeObserver !== 'undefined') {
    observer ??= new ResizeObserver(update);
    observer.observe(element);
  }
  update();
  return () => {
    bars.delete(element);
    observer?.unobserve(element);
    update();
  };
}

/*
 * Зворотний бік тієї самої задачі: сповіщення не мають накривати і кінець сторінки. На генераторі після
 * «Зупинити» картка внизу праворуч лягала на блок «Що лишилось», і прокрутити його вище було нікуди.
 *
 * `toastReserveRef` вішається на контейнер сповіщень (`Toaster`): поки є хоч одне сповіщення,
 * змінна `--toast-reserve` на `<html>` дорівнює смузі від низу вікна до верху передньої картки з проміжком.
 * Сторінка з результатами додає собі `pb-(--toast-reserve)` — і останній блок можна підняти над карткою.
 * Без сповіщень змінної немає, тож відступ зникає.
 */

export const TOAST_RESERVE_VAR = '--toast-reserve';

/** Проміжок над карткою; вміщує й «хвіст» другої картки в згорнутому стосі (sonner зсуває її на 14 px). */
const RESERVE_GAP = 24;

/** Висота смуги, яку займають сповіщення знизу вікна, px; 0 — сповіщень немає. */
export function toastReserve(list: HTMLElement | null, viewportHeight: number): number {
  if (!list) return 0;
  const front = Number.parseFloat(list.style.getPropertyValue('--front-toast-height'));
  if (!(front > 0)) return 0;
  // список стоїть fixed над низом вікна (відступ, нижня навігація, липка панель) — це теж зайнято
  const below = Math.max(0, viewportHeight - list.getBoundingClientRect().bottom);
  return Math.ceil(below + front + RESERVE_GAP);
}

function updateReserve(container: HTMLElement): void {
  const reserve = toastReserve(container.querySelector<HTMLElement>('[data-sonner-toaster]'), window.innerHeight);
  const root = document.documentElement.style;
  if (reserve > 0) root.setProperty(TOAST_RESERVE_VAR, `${reserve}px`);
  else root.removeProperty(TOAST_RESERVE_VAR);
}

/** Callback-ref для контейнера сповіщень: стежить за їхньою висотою і тримає `--toast-reserve` актуальною. */
export function toastReserveRef(container: HTMLElement | null): (() => void) | undefined {
  if (!container || typeof document === 'undefined') return undefined;
  const update = () => updateReserve(container);
  // sonner пише висоту передньої картки в style списку, а сам список зникає разом з останнім сповіщенням
  const observer = new MutationObserver(update);
  observer.observe(container, { subtree: true, childList: true, attributes: true, attributeFilter: ['style'] });
  window.addEventListener('resize', update);
  update();
  return () => {
    observer.disconnect();
    window.removeEventListener('resize', update);
    document.documentElement.style.removeProperty(TOAST_RESERVE_VAR);
  };
}
