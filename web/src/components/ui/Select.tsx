import { Check, ChevronDown } from 'lucide-react';
import { Select as RadixSelect } from 'radix-ui';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { useFieldControl } from './field-context';
import { CONTROL_BASE, CONTROL_SIZE, type ControlSize } from './styles';

export interface SelectOption<V extends string = string> {
  value: V;
  label: string;
  /** Другий рядок у списку: «4 курс, 1 зміна». */
  description?: string;
  disabled?: boolean;
  icon?: ReactNode;
}

export interface SelectProps<V extends string = string> {
  value: V | null | undefined;
  onValueChange: (value: V) => void;
  options: ReadonlyArray<SelectOption<V>>;
  placeholder?: string;
  size?: ControlSize;
  disabled?: boolean;
  id?: string;
  name?: string;
  required?: boolean;
  /** Коли підпису <Field> немає. */
  'aria-label'?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
  className?: string;
  contentClassName?: string;
}

/** Вибір з короткого списку (до ~15 варіантів). Для довгих списків з пошуком — Combobox. */
export function Select<V extends string = string>({
  value,
  onValueChange,
  options,
  placeholder = 'Оберіть…',
  size = 'md',
  disabled,
  name,
  className,
  contentClassName,
  'aria-label': ariaLabel,
  ...rest
}: SelectProps<V>) {
  const a11y = useFieldControl({ ...rest, disabled });
  return (
    <RadixSelect.Root
      value={value ?? undefined}
      onValueChange={(next) => onValueChange(next as V)}
      disabled={a11y.disabled}
      name={name}
      required={a11y.required}
    >
      <RadixSelect.Trigger
        id={a11y.id}
        aria-label={ariaLabel}
        aria-describedby={a11y['aria-describedby']}
        aria-invalid={a11y['aria-invalid']}
        className={cn(
          CONTROL_BASE,
          CONTROL_SIZE[size],
          'flex items-center justify-between gap-2 text-start data-placeholder:text-muted',
          className,
        )}
      >
        <span className="min-w-0 truncate">
          <RadixSelect.Value placeholder={placeholder} />
        </span>
        <RadixSelect.Icon asChild>
          <ChevronDown aria-hidden="true" className="size-4 text-muted" />
        </RadixSelect.Icon>
      </RadixSelect.Trigger>
      <RadixSelect.Portal>
        <RadixSelect.Content
          position="popper"
          sideOffset={4}
          collisionPadding={8}
          className={cn(
            'anim-pop z-50 max-h-(--radix-select-content-available-height) min-w-(--radix-select-trigger-width) overflow-hidden',
            'rounded-panel border border-default bg-raised text-primary shadow-popover',
            contentClassName,
          )}
        >
          <RadixSelect.Viewport className="p-1">
            {options.map((option) => (
              <RadixSelect.Item
                key={option.value}
                value={option.value}
                disabled={option.disabled}
                className={cn(
                  'relative flex cursor-default select-none items-start gap-2 rounded-control py-2 ps-8 pe-3 text-ui outline-none',
                  'data-highlighted:bg-hover data-disabled:text-disabled data-[state=checked]:font-semibold',
                )}
              >
                <span className="absolute start-2 top-2.5 flex size-4 items-center justify-center">
                  <RadixSelect.ItemIndicator>
                    <Check aria-hidden="true" className="size-4 text-accent" />
                  </RadixSelect.ItemIndicator>
                </span>
                {option.icon ? <span className="mt-0.5 text-muted">{option.icon}</span> : null}
                <span className="flex min-w-0 flex-col">
                  <RadixSelect.ItemText>{option.label}</RadixSelect.ItemText>
                  {option.description ? (
                    <span className="text-caption font-normal text-muted">{option.description}</span>
                  ) : null}
                </span>
              </RadixSelect.Item>
            ))}
          </RadixSelect.Viewport>
        </RadixSelect.Content>
      </RadixSelect.Portal>
    </RadixSelect.Root>
  );
}
