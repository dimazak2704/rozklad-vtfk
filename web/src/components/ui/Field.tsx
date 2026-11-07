import { CircleExclamationPoint } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { FieldContext } from './field-context';

export interface FieldProps {
  label: ReactNode;
  /** Підказка під полем: формат, приклад, обмеження. */
  hint?: ReactNode;
  /** Повідомлення про помилку (з zod/react-hook-form або сервера). */
  error?: string | null;
  required?: boolean;
  disabled?: boolean;
  /** Явний id контролу; інакше згенерується. */
  id?: string;
  /** Підпис збоку від поля (для перемикачів у налаштуваннях). */
  layout?: 'stacked' | 'inline';
  className?: string;
  children: ReactNode;
}

/**
 * Поле форми: підпис + контрол + підказка + помилка, повʼязані через id/aria.
 * Працює з react-hook-form: `<Field label="Логін" error={errors.username?.message}><Input {...register('username')} /></Field>`.
 */
export function Field({ label, hint, error, required = false, disabled = false, id, layout = 'stacked', className, children }: FieldProps) {
  const autoId = useId();
  const controlId = id ?? `field-${autoId}`;
  const hintId = hint ? `${controlId}-hint` : undefined;
  const errorId = error ? `${controlId}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;

  return (
    <FieldContext.Provider value={{ id: controlId, describedBy, invalid: Boolean(error), required, disabled }}>
      <div
        className={cn(
          layout === 'stacked' ? 'flex flex-col gap-1.5' : 'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1',
          className,
        )}
      >
        <label
          htmlFor={controlId}
          className={cn('text-ui font-semibold text-primary', disabled && 'text-disabled')}
        >
          {label}
          {required ? (
            <span className="ms-0.5 text-danger" aria-hidden="true">
              *
            </span>
          ) : null}
          {required ? <span className="sr-only"> (обовʼязкове поле)</span> : null}
        </label>
        {children}
        {hint ? (
          <p id={hintId} className={cn('text-caption text-muted', layout === 'inline' && 'col-span-2')}>
            {hint}
          </p>
        ) : null}
        {error ? (
          <p
            id={errorId}
            className={cn('flex items-start gap-1.5 text-caption font-medium text-danger', layout === 'inline' && 'col-span-2')}
          >
            <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
            <span>{error}</span>
          </p>
        ) : null}
      </div>
    </FieldContext.Provider>
  );
}
