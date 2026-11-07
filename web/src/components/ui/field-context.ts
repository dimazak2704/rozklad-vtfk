import { createContext, useContext } from 'react';

export interface FieldContextValue {
  id: string;
  describedBy: string | undefined;
  invalid: boolean;
  required: boolean;
  disabled: boolean;
}

export const FieldContext = createContext<FieldContextValue | null>(null);

/**
 * Атрибути доступності для контролу всередині <Field>: id, aria-describedby, aria-invalid, required.
 * Власні пропси контролу мають пріоритет.
 */
export function useFieldControl<P extends { id?: string; 'aria-describedby'?: string; 'aria-invalid'?: unknown; required?: boolean; disabled?: boolean }>(
  props: P,
): { id: string | undefined; 'aria-describedby': string | undefined; 'aria-invalid': boolean | undefined; required: boolean | undefined; disabled: boolean | undefined } {
  const field = useContext(FieldContext);
  const describedBy = [field?.describedBy, props['aria-describedby']].filter(Boolean).join(' ') || undefined;
  const invalidProp = props['aria-invalid'];
  const invalid = invalidProp === true || invalidProp === 'true' || (invalidProp === undefined && field?.invalid === true);
  return {
    id: props.id ?? field?.id,
    'aria-describedby': describedBy,
    'aria-invalid': invalid || undefined,
    required: props.required ?? (field?.required || undefined),
    disabled: props.disabled ?? (field?.disabled || undefined),
  };
}
