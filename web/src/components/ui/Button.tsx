import { Slot } from 'radix-ui';
import { useCallback, useRef, useState, type ComponentProps, type MouseEvent, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { buttonClass, type ButtonSize, type ButtonVariant } from './styles';
import { Spinner } from './Spinner';

export interface ButtonProps extends ComponentProps<'button'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Показує спінер, блокує повторне натискання і ставить aria-busy. Колір кнопки лишається повним. */
  loading?: boolean;
  /** Текст для читачів екрана під час завантаження. */
  loadingLabel?: string;
  /** Видимий підпис на час завантаження: «Публікуємо…». Без нього лишається звичайний підпис. */
  loadingText?: ReactNode;
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
  loadingText,
  iconStart,
  iconEnd,
  asChild = false,
  static: isStatic = false,
  className,
  disabled,
  children,
  type,
  style,
  onClick,
  onKeyDown,
  ...rest
}: ButtonProps) {
  const node = useRef<HTMLButtonElement | null>(null);
  const [restingWidth, setRestingWidth] = useState<number | null>(null);
  const [wasLoading, setWasLoading] = useState(loading);

  // ширина фіксується на мить початку завантаження, щоб коротший чи довший підпис не смикав розкладку
  if (loading !== wasLoading) {
    setWasLoading(loading);
    if (!loading) setRestingWidth(null);
  }

  const setRef = useCallback(
    (element: HTMLButtonElement | null) => {
      node.current = element;
      if (typeof ref === 'function') ref(element);
      else if (ref) ref.current = element;
    },
    [ref],
  );

  const classes = cn(
    buttonClass({ variant, size }),
    !isStatic && variant !== 'link' && (size === 'sm' ? 'enabled:active:scale-[0.96]' : 'enabled:active:scale-[0.98]'),
    iconStart && variant !== 'link' && (size === 'lg' ? 'ps-3' : 'ps-2.5'),
    iconEnd && variant !== 'link' && (size === 'lg' ? 'pe-3' : 'pe-2.5'),
    loading && 'pointer-events-none cursor-progress',
    className,
  );

  if (asChild) {
    return (
      <Slot.Root ref={ref} className={classes} style={style} onClick={onClick} onKeyDown={onKeyDown} {...rest}>
        {children}
      </Slot.Root>
    );
  }

  const handleClick = (event: MouseEvent<HTMLButtonElement>) => {
    // під час завантаження повторне натискання (зокрема Enter у формі) нічого не робить
    if (loading) {
      event.preventDefault();
      return;
    }
    onClick?.(event);
  };

  const captureWidth = () => {
    if (!loading && node.current) setRestingWidth(node.current.offsetWidth);
  };

  return (
    <button
      ref={setRef}
      type={type ?? 'button'}
      className={classes}
      disabled={disabled}
      aria-disabled={loading || undefined}
      aria-busy={loading || undefined}
      style={loading && restingWidth ? { minWidth: restingWidth, ...style } : style}
      {...rest}
      onPointerDown={(event) => {
        captureWidth();
        rest.onPointerDown?.(event);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') captureWidth();
        onKeyDown?.(event);
      }}
      onClick={handleClick}
    >
      {loading ? <Spinner size="md" label={loadingLabel} /> : iconStart}
      {loading && loadingText ? loadingText : children}
      {iconEnd}
    </button>
  );
}
