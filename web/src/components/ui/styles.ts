/** Спільні класи контролів: кнопки й поля. Окремий файл, щоб компоненти лишалися «чистими» для Fast Refresh. */
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost' | 'shell' | 'link';
export type ButtonSize = 'sm' | 'md' | 'lg';

const BASE =
  'relative inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap rounded-control text-ui font-semibold ' +
  'transition-[background-color,border-color,color,scale] duration-100 ease-out ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus ' +
  'disabled:cursor-not-allowed aria-disabled:cursor-not-allowed';

/**
 * Стани: спокій → наведення (hover) → натискання (active, на тон темніше) → фокус → вимкнено.
 * Вимкнена кнопка не «прозоріє»: заглиблене тло і приглушений текст, щоб читалася як недоступна, а не бліда.
 */
const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-on-accent hover:bg-accent-hover active:bg-accent-active ' +
    'disabled:border disabled:border-separator disabled:bg-sunken disabled:text-disabled',
  secondary:
    'border border-default bg-surface text-primary hover:bg-hover active:bg-active ' +
    'disabled:border-separator disabled:bg-sunken disabled:text-disabled',
  ghost: 'text-secondary hover:bg-hover hover:text-primary active:bg-active disabled:bg-transparent disabled:text-disabled',
  danger:
    'bg-danger text-on-accent hover:bg-danger-hover active:bg-danger-active ' +
    'disabled:border disabled:border-separator disabled:bg-sunken disabled:text-disabled',
  'danger-ghost': 'text-danger hover:bg-danger-subtle active:bg-danger-subtle disabled:bg-transparent disabled:text-disabled',
  shell:
    'text-on-shell hover:bg-shell-hover active:bg-shell-raised focus-visible:outline-focus-on-shell disabled:text-on-shell-muted',
  link: 'h-auto px-0 font-semibold text-accent underline-offset-4 hover:underline disabled:text-disabled disabled:no-underline',
};

/** Висота 32/36/44; на дотику — 40/44 (мінімальна ціль натискання). */
const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 pointer-coarse:h-10',
  md: 'h-9 px-3.5 pointer-coarse:h-11',
  lg: 'h-11 px-4',
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
  'focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus ' +
  'aria-invalid:border-danger aria-invalid:hover:border-danger ' +
  'disabled:cursor-not-allowed disabled:border-separator disabled:bg-sunken disabled:text-disabled';

/** Висота й шрифт: на телефоні 16px, щоб iOS не збільшував сторінку; на дотику поле вище. */
export const CONTROL_SIZE: Record<ControlSize, string> = {
  sm: 'h-8 px-2.5 text-body sm:text-ui pointer-coarse:h-10',
  md: 'h-9 px-3 text-body sm:text-ui pointer-coarse:h-11',
  lg: 'h-12 px-4 text-body',
};
