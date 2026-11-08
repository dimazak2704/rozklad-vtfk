import { Minus, Plus } from 'lucide-react';
import { useState, type ComponentProps, type KeyboardEvent } from 'react';
import { cn } from '@/lib/cn';
import { useFieldControl } from './field-context';
import { CONTROL_BASE, CONTROL_SIZE, type ControlSize } from './styles';

export interface NumberInputProps
  extends Omit<ComponentProps<'input'>, 'value' | 'defaultValue' | 'onChange' | 'size' | 'type' | 'min' | 'max' | 'step'> {
  value: number | null;
  onValueChange: (value: number | null) => void;
  min?: number;
  max?: number;
  step?: number;
  /** Скільки знаків після коми дозволено (0 — лише цілі). */
  precision?: number;
  size?: ControlSize;
  /** Підпис одиниць праворуч від числа: «год», «хв». */
  unit?: string;
  /** Підписи кнопок для читачів екрана. */
  decrementLabel?: string;
  incrementLabel?: string;
}

function clamp(value: number, min?: number, max?: number): number {
  let out = value;
  if (min !== undefined) out = Math.max(min, out);
  if (max !== undefined) out = Math.min(max, out);
  return out;
}

function roundTo(value: number, precision: number): number {
  const factor = 10 ** precision;
  return Math.round(value * factor) / factor;
}

function formatNumber(value: number | null, precision: number): string {
  if (value === null) return '';
  return precision > 0 ? String(roundTo(value, precision)).replace('.', ',') : String(Math.round(value));
}

/** Число з кнопками −/+ і стрілками. Кома й крапка як роздільник; межі застосовуються при виході з поля. */
export function NumberInput({
  ref,
  value,
  onValueChange,
  min,
  max,
  step = 1,
  precision = 0,
  size = 'md',
  unit,
  decrementLabel = 'Зменшити',
  incrementLabel = 'Збільшити',
  className,
  onBlur,
  onKeyDown,
  ...props
}: NumberInputProps) {
  const a11y = useFieldControl(props);
  const [draft, setDraft] = useState<string | null>(null);
  const text = draft ?? formatNumber(value, precision);

  const commit = (next: number | null) => {
    setDraft(null);
    onValueChange(next === null ? null : roundTo(clamp(next, min, max), precision));
  };

  const parse = (raw: string): number | null => {
    const normalized = raw.trim().replace(/\s/g, '').replace(',', '.');
    if (normalized === '' || normalized === '-') return null;
    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : null;
  };

  const nudge = (direction: 1 | -1, multiplier = 1) => {
    const base = parse(text) ?? value ?? min ?? 0;
    commit(base + direction * step * multiplier);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    onKeyDown?.(event);
    if (event.defaultPrevented) return;
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault();
      nudge(event.key === 'ArrowUp' ? 1 : -1, event.shiftKey ? 10 : 1);
    } else if (event.key === 'Enter') {
      commit(parse(text));
    }
  };

  const atMin = value !== null && min !== undefined && value <= min;
  const atMax = value !== null && max !== undefined && value >= max;
  const stepButton =
    'flex h-full w-9 shrink-0 items-center justify-center text-secondary transition-colors duration-100 pointer-coarse:w-11 ' +
    'hover:bg-hover hover:text-primary active:bg-active ' +
    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus disabled:pointer-events-none disabled:text-disabled';

  return (
    <div
      className={cn(
        CONTROL_BASE,
        CONTROL_SIZE[size],
        'flex items-stretch overflow-hidden p-0 focus-within:border-accent focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-focus',
        a11y['aria-invalid'] && 'border-danger',
        a11y.disabled && 'border-separator bg-sunken text-disabled',
        className,
      )}
    >
      <button
        type="button"
        tabIndex={-1}
        aria-label={decrementLabel}
        className={cn(stepButton, 'border-e border-default')}
        disabled={a11y.disabled || atMin}
        onClick={() => nudge(-1)}
      >
        <Minus aria-hidden="true" className="size-3.5" />
      </button>
      {/* число й одиниця стоять разом праворуч: «4 год», а не «4 … год» */}
      <div className="flex min-w-0 flex-1 items-center justify-end gap-1 pe-3">
        <input
        ref={ref}
        type="text"
        inputMode={precision > 0 ? 'decimal' : 'numeric'}
        autoComplete="off"
        role="spinbutton"
        aria-valuenow={value ?? undefined}
        aria-valuemin={min}
        aria-valuemax={max}
        {...props}
        {...a11y}
        value={text}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={(event) => {
          if (draft !== null) commit(parse(draft));
          onBlur?.(event);
        }}
        onKeyDown={handleKeyDown}
        className="w-full min-w-0 bg-transparent text-end figures outline-none disabled:cursor-not-allowed"
      />
      {unit ? (
        <span className="shrink-0 text-muted" aria-hidden="true">
          {unit}
        </span>
      ) : null}
      </div>
      <button
        type="button"
        tabIndex={-1}
        aria-label={incrementLabel}
        className={cn(stepButton, 'border-s border-default')}
        disabled={a11y.disabled || atMax}
        onClick={() => nudge(1)}
      >
        <Plus aria-hidden="true" className="size-3.5" />
      </button>
    </div>
  );
}
