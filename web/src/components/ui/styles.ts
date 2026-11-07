/** Спільні класи контролів: кнопки й поля. Окремий файл, щоб компоненти лишалися «чистими» для Fast Refresh. */
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost' | 'shell' | 'link';
export type ButtonSize = 'sm' | 'md' | 'lg';

const BASE =
  'relative inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap rounded-control font-semibold ' +
  'transition-[background-color,border-color,color,scale] duration-150 ease-out ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus ' +
  'disabled:cursor-not-allowed disabled:opacity-50 aria-disabled:cursor-not-allowed aria-disabled:opacity-50';

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-on-accent hover:bg-accent-hover',
  secondary: 'border border-default bg-surface text-primary hover:border-strong hover:bg-hover',
  ghost: 'text-secondary hover:bg-hover hover:text-primary',
  danger: 'bg-danger text-on-accent hover:bg-danger-hover',
  'danger-ghost': 'text-danger hover:bg-danger-subtle',
  shell: 'text-on-shell hover:bg-shell-hover focus-visible:outline-focus-on-shell',
  link: 'h-auto px-0 font-semibold text-accent underline-offset-4 hover:underline',
};

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-caption',
  md: 'h-9 px-3.5 text-ui',
  lg: 'h-11 px-5 text-body',
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
  'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus ' +
  'aria-invalid:border-danger aria-invalid:hover:border-danger ' +
  'disabled:cursor-not-allowed disabled:border-default disabled:bg-sunken disabled:text-disabled';

/** Висота й шрифт: на телефоні 16px, щоб iOS не збільшував сторінку. */
export const CONTROL_SIZE: Record<ControlSize, string> = {
  sm: 'h-8 px-2.5 text-body sm:text-caption',
  md: 'h-9 px-3 text-body sm:text-ui',
  lg: 'h-11 px-3.5 text-body',
};
