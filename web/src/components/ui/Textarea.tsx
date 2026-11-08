import type { ComponentProps } from 'react';
import { cn } from '@/lib/cn';
import { useFieldControl } from './field-context';
import { CONTROL_BASE } from './styles';

export interface TextareaProps extends ComponentProps<'textarea'> {
  /** Росте разом з текстом (де браузер підтримує field-sizing). */
  autoGrow?: boolean;
}

export function Textarea({ ref, autoGrow = true, className, rows = 3, ...props }: TextareaProps) {
  const a11y = useFieldControl(props);
  return (
    <textarea
      ref={ref}
      rows={rows}
      {...props}
      {...a11y}
      className={cn(
        CONTROL_BASE,
        'min-h-24 px-3 py-2 text-body sm:text-ui',
        autoGrow && 'field-sizing-content max-h-80',
        className,
      )}
    />
  );
}
