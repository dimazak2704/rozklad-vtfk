import { Slot } from 'radix-ui';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { buttonClass, type ButtonSize, type ButtonVariant } from './styles';
import { Spinner } from './Spinner';

export interface ButtonProps extends ComponentProps<'button'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Показує спінер, блокує повторне натискання і ставить aria-busy. */
  loading?: boolean;
  /** Текст для читачів екрана під час завантаження. */
  loadingLabel?: string;
  iconStart?: ReactNode;
  iconEnd?: ReactNode;
  /** Рендерить дочірній елемент (наприклад, Link) зі стилями кнопки. */
  asChild?: boolean;
  /** Без стиснення при натисканні (для кнопок, де рух відволікає). */
  static?: boolean;
}

export function Button({
  ref,
  variant = 'secondary',
  size = 'md',
  loading = false,
  loadingLabel = 'Виконується',
  iconStart,
  iconEnd,
  asChild = false,
  static: isStatic = false,
  className,
  disabled,
  children,
  type,
  ...rest
}: ButtonProps) {
  const classes = cn(
    buttonClass({ variant, size }),
    !isStatic && variant !== 'link' && 'enabled:active:scale-[0.96]',
    iconStart && variant !== 'link' && 'ps-2.5',
    iconEnd && variant !== 'link' && 'pe-2.5',
    className,
  );

  if (asChild) {
    return (
      <Slot.Root ref={ref} className={classes} {...rest}>
        {children}
      </Slot.Root>
    );
  }

  return (
    <button
      ref={ref}
      type={type ?? 'button'}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner size="sm" label={loadingLabel} /> : iconStart}
      {children}
      {iconEnd}
    </button>
  );
}
