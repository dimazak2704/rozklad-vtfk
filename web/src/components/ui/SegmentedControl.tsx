import { ToggleGroup } from 'radix-ui';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface SegmentOption<V extends string = string> {
  value: V;
  label: ReactNode;
  icon?: ReactNode;
  disabled?: boolean;
  /** Текст для aria-label, коли видно лише іконку. */
  ariaLabel?: string;
}

export interface SegmentedControlProps<V extends string = string> {
  value: V;
  onValueChange: (value: V) => void;
  options: ReadonlyArray<SegmentOption<V>>;
  'aria-label': string;
  size?: 'sm' | 'md';
  /** На всю ширину контейнера (мобільні перемикачі «День / Тиждень»). */
  stretch?: boolean;
  tone?: 'default' | 'on-shell';
  className?: string;
}

/** Перемикач подання: «День / Тиждень», «Сьогодні / Завтра». Порожнім бути не може. */
export function SegmentedControl<V extends string = string>({
  value,
  onValueChange,
  options,
  size = 'md',
  stretch = false,
  tone = 'default',
  className,
  'aria-label': ariaLabel,
}: SegmentedControlProps<V>) {
  return (
    <ToggleGroup.Root
      type="single"
      value={value}
      onValueChange={(next) => {
        // повторний клік по активному сегменту не має скидати вибір
        if (next) onValueChange(next as V);
      }}
      aria-label={ariaLabel}
      className={cn(
        'scrollbar-none inline-flex max-w-full items-stretch gap-0.5 overflow-x-auto rounded-control p-0.5',
        tone === 'default' ? 'bg-sunken ring-1 ring-[var(--border-color-default)] ring-inset' : 'bg-shell-raised',
        stretch && 'flex w-full',
        className,
      )}
    >
      {options.map((option) => (
        <ToggleGroup.Item
          key={option.value}
          value={option.value}
          disabled={option.disabled}
          aria-label={option.ariaLabel}
          className={cn(
            'inline-flex items-center justify-center gap-1.5 rounded-[calc(var(--radius-control)-2px)] px-3 font-semibold whitespace-nowrap',
            'transition-[background-color,color] duration-100 ease-out',
            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
            'disabled:cursor-not-allowed disabled:opacity-50',
            size === 'sm' ? 'h-7 text-caption' : 'h-8 text-ui',
            stretch && 'flex-1',
            tone === 'default'
              ? 'text-secondary hover:text-primary data-[state=on]:bg-raised data-[state=on]:text-primary data-[state=on]:shadow-[0_1px_2px_oklch(24.5%_0.03_258/0.12)]'
              : 'text-on-shell-muted hover:text-on-shell data-[state=on]:bg-shell-hover data-[state=on]:text-on-shell',
          )}
        >
          {option.icon}
          {option.label}
        </ToggleGroup.Item>
      ))}
    </ToggleGroup.Root>
  );
}
