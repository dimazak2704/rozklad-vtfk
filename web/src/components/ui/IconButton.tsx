import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { buttonClass, type ButtonVariant } from './styles';
import { Spinner } from './Spinner';
import { Tooltip } from './Tooltip';

type IconButtonSize = 'sm' | 'md' | 'lg';

const SIZES: Record<IconButtonSize, string> = {
  sm: 'size-8 px-0',
  md: 'size-9 px-0',
  lg: 'size-11 px-0',
};

export interface IconButtonProps extends Omit<ComponentProps<'button'>, 'aria-label' | 'children'> {
  /** Обовʼязковий підпис: стає aria-label і текстом підказки. */
  label: string;
  icon: ReactNode;
  variant?: ButtonVariant;
  size?: IconButtonSize;
  /** Клавіатурне скорочення для підказки, напр. `mod+k`. */
  shortcut?: string;
  tooltipSide?: 'top' | 'right' | 'bottom' | 'left';
  /** Сховати підказку (коли поруч уже видно той самий текст). */
  hideTooltip?: boolean;
  loading?: boolean;
  /** Стан перемикача (aria-pressed). */
  pressed?: boolean;
}

/** Кнопка лише з іконкою. Підпис обовʼязковий: без нього кнопка німа для читача екрана. */
export function IconButton({
  ref,
  label,
  icon,
  variant = 'ghost',
  size = 'md',
  shortcut,
  tooltipSide = 'top',
  hideTooltip = false,
  loading = false,
  pressed,
  className,
  disabled,
  type,
  ...rest
}: IconButtonProps) {
  return (
    <Tooltip content={label} shortcut={shortcut} side={tooltipSide} disabled={hideTooltip}>
      <button
        ref={ref}
        type={type ?? 'button'}
        aria-label={label}
        aria-pressed={pressed}
        aria-busy={loading || undefined}
        disabled={disabled || loading}
        className={cn(
          buttonClass({ variant }),
          SIZES[size],
          'enabled:active:scale-[0.96]',
          pressed && 'bg-selected text-accent hover:bg-selected-hover',
          className,
        )}
        {...rest}
      >
        {loading ? <Spinner size="sm" label={label} /> : icon}
      </button>
    </Tooltip>
  );
}
