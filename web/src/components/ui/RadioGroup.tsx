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
          const descriptionId = option.description ? `${itemId}-description` : undefined;
          return (
            <div key={option.value} className="relative flex min-h-11 items-start gap-3 py-2.5 sm:min-h-9 sm:py-1.5">
              <RadixRadioGroup.Item
                id={itemId}
                value={option.value}
                disabled={option.disabled}
                aria-describedby={descriptionId}
                className={cn(
                  // над підписом-накладкою (after:inset-0): сам кружок теж отримує клік
                  'relative z-10 mt-px flex size-[1.125rem] shrink-0 items-center justify-center rounded-full border-[1.5px] border-control bg-surface',
                  'transition-[border-color] duration-100 ease-out hover:border-strong',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                  'data-[state=checked]:border-accent',
                  'disabled:cursor-not-allowed disabled:border-separator disabled:bg-sunken',
                )}
              >
                <RadixRadioGroup.Indicator className="block size-2.5 rounded-full bg-accent" />
              </RadixRadioGroup.Item>
              <div className="flex flex-col gap-0.5">
                <label htmlFor={itemId} className="cursor-pointer text-ui text-primary select-none after:absolute after:inset-0">
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
