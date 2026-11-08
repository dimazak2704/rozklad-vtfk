import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { buttonClass, type ButtonVariant } from './styles';
import { Spinner } from './Spinner';
import { Tooltip } from './Tooltip';

type IconButtonSize = 'sm' | 'md' | 'lg';

/** 32/36/44 px; на дотику — щонайменше 44 px. */
const SIZES: Record<IconButtonSize, string> = {
  sm: 'size-8 px-0 pointer-coarse:size-11',
  md: 'size-9 px-0 pointer-coarse:size-11',
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
          // «кошик» у спокої нейтральний: червоніє лише при наведенні чи фокусі, щоб рядки не рябіли
          variant === 'danger-ghost' &&
            'text-secondary hover:bg-cancel-subtle hover:text-danger focus-visible:bg-cancel-subtle focus-visible:text-danger',
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
