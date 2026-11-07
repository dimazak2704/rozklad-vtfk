import { Switch as RadixSwitch } from 'radix-ui';
import { useId, type ComponentProps, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface SwitchProps extends Omit<ComponentProps<typeof RadixSwitch.Root>, 'children'> {
  label?: ReactNode;
  description?: ReactNode;
  /** Підпис ліворуч, перемикач праворуч (рядки налаштувань). */
  labelPosition?: 'start' | 'end';
}

/** Перемикач для налаштувань, що діють одразу (без кнопки «Зберегти» — тоді краще Checkbox). */
export function Switch({ label, description, labelPosition = 'end', className, id, disabled, ...props }: SwitchProps) {
  const autoId = useId();
  const controlId = id ?? `switch-${autoId}`;
  const descriptionId = description ? `${controlId}-description` : undefined;

  const control = (
    <RadixSwitch.Root
      id={controlId}
      disabled={disabled}
      aria-describedby={descriptionId}
      className={cn(
        'relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border border-transparent',
        'bg-[var(--border-color-strong)] transition-[background-color] duration-150 ease-out',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        'data-[state=checked]:bg-accent disabled:cursor-not-allowed disabled:opacity-50',
        !label && className,
      )}
      {...props}
    >
      <RadixSwitch.Thumb
        className={cn(
          'block size-4 rounded-full bg-[var(--fill-surface)] shadow-[0_1px_2px_oklch(0%_0_0/0.25)]',
          'translate-x-0.5 transition-transform duration-150 ease-out data-[state=checked]:translate-x-[1.125rem]',
          'rtl:-translate-x-0.5 rtl:data-[state=checked]:-translate-x-[1.125rem]',
        )}
      />
    </RadixSwitch.Root>
  );

  if (!label) return control;

  return (
    <div className={cn('flex items-start gap-3', labelPosition === 'start' && 'flex-row-reverse justify-between', className)}>
      <span className="mt-0.5">{control}</span>
      <div className="flex min-w-0 flex-col gap-0.5">
        <label
          htmlFor={controlId}
          className={cn('cursor-pointer text-ui text-primary select-none', disabled && 'cursor-not-allowed text-disabled')}
        >
          {label}
        </label>
        {description ? (
          <p id={descriptionId} className="text-caption text-muted">
            {description}
          </p>
        ) : null}
      </div>
    </div>
  );
}
