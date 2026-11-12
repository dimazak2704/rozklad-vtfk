/** Спільні класи контролів: кнопки й поля. Окремий файл, щоб компоненти лишалися «чистими» для Fast Refresh. */
import { cn } from '@/lib/cn';

/**
 * `ghost` — вбудована дія текстом акценту («+ Додати відсутність», «Ще 5 подій»): видно, що це можна натиснути.
 * `quiet` — нейтральна кнопка без рамки для іконок і перемикачів (стрілки дати, меню).
 * `shell` — застаріле (колишня синя смуга), тепер те саме, що `quiet`.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'quiet' | 'danger' | 'danger-ghost' | 'shell' | 'link';
export type ButtonSize = 'sm' | 'md' | 'lg';

const BASE =
  'relative inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap rounded-control text-ui font-[550] ' +
  'transition-[background-color,border-color,color,scale] duration-150 ease-out ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus ' +
  'disabled:cursor-not-allowed aria-disabled:cursor-not-allowed';

/**
 * Стани: спокій → наведення (hover) → натискання (active, на тон темніше) → фокус → вимкнено.
 * Вимкнена кнопка не «прозоріє»: заглиблене тло і приглушений текст, щоб читалася як недоступна, а не бліда.
 * Вага: 650 лише в заповнених (головна, небезпечна) — єдина заповнена дія веде і кольором, і вагою; решта 550.
 * Одна заповнена кнопка на область; небезпечна заповнена — лише всередині діалогу підтвердження.
 * Другорядна тримає рамку в усіх станах: наведення — тло й темніша рамка, натискання — ще на тон темніше.
 */
const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    'bg-accent font-[650] text-on-accent hover:bg-accent-hover active:bg-accent-active ' +
    'disabled:border disabled:border-separator disabled:bg-sunken disabled:text-disabled',
  secondary:
    'border border-default bg-surface text-primary hover:border-control hover:bg-hover active:border-control active:bg-active ' +
    'disabled:border-separator disabled:bg-sunken disabled:text-disabled',
  ghost: 'text-accent hover:bg-hover active:bg-active disabled:bg-transparent disabled:text-disabled',
  quiet: 'text-secondary hover:bg-hover hover:text-primary active:bg-active disabled:bg-transparent disabled:text-disabled',
  danger:
    'bg-danger font-[650] text-on-danger hover:bg-danger-hover active:bg-danger-active ' +
    'disabled:border disabled:border-separator disabled:bg-sunken disabled:text-disabled',
  'danger-ghost': 'text-danger hover:bg-danger-subtle active:bg-danger-subtle disabled:bg-transparent disabled:text-disabled',
  shell: 'text-secondary hover:bg-hover hover:text-primary active:bg-active disabled:bg-transparent disabled:text-disabled',
  link: 'h-auto px-0 font-[650] text-accent underline-offset-4 hover:underline disabled:text-disabled disabled:no-underline',
};

/** Висота 32 (компактна, у таблицях) / 40 / 48; на дотику — щонайменше 44 (мінімальна ціль натискання). */
const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 pointer-coarse:h-11',
  md: 'h-10 px-4 pointer-coarse:h-11',
  lg: 'h-12 px-5 text-body',
};

/** Утиліта для посилань, які мають виглядати як кнопка (`<Link className={buttonClass(...)}>`). */
export function buttonClass({
  variant = 'secondary',
  size = 'md',
  className,
}: { variant?: ButtonVariant; size?: ButtonSize; className?: string } = {}): string {
  return cn(BASE, VARIANTS[variant], variant === 'link' ? 'h-auto' : SIZES[size], className);
}

export type ControlSize = 'sm' | 'md' | 'lg';

/** Спільний вигляд текстових контролів (Input, Select, Combobox, Textarea). */
export const CONTROL_BASE =
  'w-full min-w-0 rounded-control border border-control bg-surface text-primary ' +
  'transition-[border-color,background-color] duration-100 ease-out ' +
  'placeholder:text-muted hover:border-strong ' +
  // фокус — те саме кільце 2 px з відступом 2 px, що й у кнопок: один вигляд фокуса на весь застосунок
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus ' +
  'aria-invalid:border-danger aria-invalid:hover:border-danger ' +
  'disabled:cursor-not-allowed disabled:border-separator disabled:bg-sunken disabled:text-disabled';

/** Висота 36 / 44 / 52 і шрифт: на телефоні 16 px, щоб iOS не збільшував сторінку; на дотику — щонайменше 44. */
export const CONTROL_SIZE: Record<ControlSize, string> = {
  sm: 'h-9 px-3 text-body sm:text-ui pointer-coarse:h-11',
  md: 'h-11 px-3.5 text-body sm:text-ui',
  lg: 'h-13 rounded-popover px-4 text-body',
};
