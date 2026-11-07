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
        value={value ?? undefined}
        onValueChange={(next) => onValueChange(next as V)}
        orientation={orientation}
        disabled={disabled}
        name={name}
        aria-labelledby={legendId}
        aria-label={legendId ? undefined : ariaLabel}
        className={cn('flex gap-2.5', orientation === 'vertical' ? 'flex-col' : 'flex-row flex-wrap gap-x-5')}
      >
        {options.map((option) => {
          const itemId = `${groupId}-${option.value}`;
          const descriptionId = option.description ? `${itemId}-description` : undefined;
          return (
            <div key={option.value} className="flex items-start gap-2.5">
              <RadixRadioGroup.Item
                id={itemId}
                value={option.value}
                disabled={option.disabled}
                aria-describedby={descriptionId}
                className={cn(
                  'mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border border-control bg-surface',
                  'transition-[border-color] duration-100 ease-out hover:border-strong',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                  'data-[state=checked]:border-accent disabled:cursor-not-allowed disabled:opacity-50',
                )}
              >
                <RadixRadioGroup.Indicator className="block size-2 rounded-full bg-accent" />
              </RadixRadioGroup.Item>
              <div className="flex flex-col gap-0.5">
                <label htmlFor={itemId} className="cursor-pointer text-ui text-primary select-none">
                  {option.label}
                </label>
                {option.description ? (
                  <p id={descriptionId} className="text-caption text-muted">
                    {option.description}
                  </p>
                ) : null}
              </div>
            </div>
          );
        })}
      </RadixRadioGroup.Root>
    </div>
  );
}
