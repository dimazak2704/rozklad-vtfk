import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { useFieldControl } from './field-context';
import { CONTROL_BASE, CONTROL_SIZE, type ControlSize } from './styles';

export interface InputProps extends Omit<ComponentProps<'input'>, 'size'> {
  size?: ControlSize;
  /** Іконка на початку поля (лупа, календар). */
  iconStart?: ReactNode;
  /** Елемент у кінці поля: кнопка очищення, клавіша. */
  endSlot?: ReactNode;
  /** Клас обгортки, коли є іконка чи слот. */
  wrapperClassName?: string;
}

export function Input({ ref, size = 'md', iconStart, endSlot, className, wrapperClassName, ...props }: InputProps) {
  const a11y = useFieldControl(props);
  const input = (
    <input
      ref={ref}
      {...props}
      {...a11y}
      className={cn(CONTROL_BASE, CONTROL_SIZE[size], iconStart && 'ps-9', endSlot && 'pe-10', className)}
    />
  );
  if (!iconStart && !endSlot) return input;
  return (
    <div className={cn('relative flex w-full items-center', wrapperClassName)}>
      {iconStart ? (
        <span className="pointer-events-none absolute inset-y-0 start-3 flex items-center text-muted" aria-hidden="true">
          {iconStart}
        </span>
      ) : null}
      {input}
      {endSlot ? <span className="absolute inset-y-0 end-1 flex items-center">{endSlot}</span> : null}
    </div>
  );
}
