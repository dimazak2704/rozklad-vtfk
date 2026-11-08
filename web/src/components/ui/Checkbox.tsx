import { Check, Minus } from 'lucide-react';
import { Checkbox as RadixCheckbox } from 'radix-ui';
import { useId, type ComponentProps, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface CheckboxProps extends Omit<ComponentProps<typeof RadixCheckbox.Root>, 'children'> {
  /** Підпис праворуч; клік по ньому теж перемикає. */
  label?: ReactNode;
  description?: ReactNode;
}

/** Прапорець з підтримкою «частково» (`checked="indeterminate"`). */
export function Checkbox({ label, description, className, id, disabled, ...props }: CheckboxProps) {
  const autoId = useId();
  const controlId = id ?? `checkbox-${autoId}`;
  const descriptionId = description ? `${controlId}-description` : undefined;

  const box = (
    <RadixCheckbox.Root
      id={controlId}
      disabled={disabled}
      aria-describedby={descriptionId}
      className={cn(
        'peer flex size-[1.125rem] shrink-0 items-center justify-center rounded-plate border-[1.5px] border-control bg-surface',
        'transition-[background-color,border-color] duration-100 ease-out hover:border-strong',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        'data-[state=checked]:border-accent data-[state=checked]:bg-accent data-[state=checked]:text-on-accent',
        'data-[state=indeterminate]:border-accent data-[state=indeterminate]:bg-accent data-[state=indeterminate]:text-on-accent',
        'disabled:cursor-not-allowed disabled:border-separator disabled:bg-sunken disabled:text-disabled',
        !label && className,
      )}
      {...props}
    >
      <RadixCheckbox.Indicator className="flex items-center justify-center">
        {props.checked === 'indeterminate' ? (
          <Minus aria-hidden="true" className="size-3.5 [stroke-width:2.5]" />
        ) : (
          <Check aria-hidden="true" className="size-3.5 [stroke-width:2.5]" />
        )}
      </RadixCheckbox.Indicator>
    </RadixCheckbox.Root>
  );

  if (!label) return box;

  return (
    <div className={cn('relative flex min-h-11 items-start gap-3 py-2.5 sm:min-h-9 sm:py-1.5', className)}>
      {/* над підписом-накладкою (after:inset-0): сам прапорець теж отримує клік */}
      <span className="relative z-10 flex h-5 items-center">{box}</span>
      <div className="flex flex-col gap-0.5">
        <label
          htmlFor={controlId}
          className={cn(
            'cursor-pointer text-ui text-primary select-none after:absolute after:inset-0',
            disabled && 'cursor-not-allowed text-disabled',
          )}
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
