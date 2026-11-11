import { RadioGroup as RadixRadioGroup } from 'radix-ui';
import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface RadioOption<V extends string = string> {
  value: V;
  label: ReactNode;
  description?: ReactNode;
  disabled?: boolean;
}

export interface RadioGroupProps<V extends string = string> {
  value: V | null | undefined;
  onValueChange: (value: V) => void;
  options: ReadonlyArray<RadioOption<V>>;
  /** Видимий заголовок групи (стає aria-labelledby). */
  legend?: ReactNode;
  'aria-label'?: string;
  orientation?: 'vertical' | 'horizontal';
  disabled?: boolean;
  name?: string;
  className?: string;
}

/** Взаємовиключний вибір з 2–6 варіантів, коли всі варіанти варто бачити одразу. */
export function RadioGroup<V extends string = string>({
  value,
  onValueChange,
  options,
  legend,
  orientation = 'vertical',
  disabled,
  name,
  className,
  'aria-label': ariaLabel,
}: RadioGroupProps<V>) {
  const groupId = useId();
  const legendId = legend ? `${groupId}-legend` : undefined;

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      {legend ? (
        <span id={legendId} className="text-ui font-semibold text-primary">
          {legend}
        </span>
      ) : null}
      <RadixRadioGroup.Root
        // '' — нічого не обрано, але компонент лишається керованим (без попередження React при першому виборі)
        value={value ?? ''}
        onValueChange={(next) => onValueChange(next as V)}
        orientation={orientation}
        disabled={disabled}
        name={name}
        aria-labelledby={legendId}
        aria-label={legendId ? undefined : ariaLabel}
        className={cn('flex', orientation === 'vertical' ? 'flex-col' : 'flex-row flex-wrap gap-x-6')}
      >
        {options.map((option) => {
          const itemId = `${groupId}-${option.value}`;
          const labelId = `${itemId}-label`;
          const descriptionId = option.description ? `${itemId}-description` : undefined;
          return (
            // увесь рядок — <label>: на дотику ціль 44 px, а не 18-піксельний кружок із підписом
            <label
              key={option.value}
              htmlFor={itemId}
              className={cn(
                'flex min-h-11 items-start gap-3 py-2.5 select-none sm:min-h-9 sm:py-1.5 pointer-coarse:min-h-11 pointer-coarse:py-2.5',
                option.disabled || disabled ? 'cursor-not-allowed' : 'cursor-pointer',
              )}
            >
              <RadixRadioGroup.Item
                id={itemId}
                value={option.value}
                disabled={option.disabled}
                aria-labelledby={labelId}
                aria-describedby={descriptionId}
                className={cn(
                  'mt-px flex size-[1.125rem] shrink-0 items-center justify-center rounded-full border-[1.5px] border-control bg-surface',
                  'transition-[border-color] duration-100 ease-out hover:border-strong',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                  'data-[state=checked]:border-accent',
                  'disabled:cursor-not-allowed disabled:border-separator disabled:bg-sunken',
                )}
              >
                <RadixRadioGroup.Indicator className="block size-2.5 rounded-full bg-accent" />
              </RadixRadioGroup.Item>
              <span className="flex flex-col gap-0.5">
                <span id={labelId} className="text-ui text-primary">
                  {option.label}
                </span>
                {option.description ? (
                  <span id={descriptionId} className="text-caption text-muted">
                    {option.description}
                  </span>
                ) : null}
              </span>
            </label>
          );
        })}
      </RadixRadioGroup.Root>
    </div>
  );
}
