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
