import { Slider } from 'radix-ui';
import { useId } from 'react';

interface WeightSliderProps {
  label: string;
  hint: string;
  value: number;
  max: number;
  defaultValue: number;
  onValueChange: (value: number) => void;
  disabled?: boolean;
}

/** Вага одного мʼякого обмеження: повзунок 0…max, число поруч, пояснення під ним. 0 — не враховувати. */
export function WeightSlider({ label, hint, value, max, defaultValue, onValueChange, disabled }: WeightSliderProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3">
        <span id={`${id}-label`} className="text-ui font-semibold text-primary">
          {label}
        </span>
        <span className="text-caption text-muted figures">
          <span className="font-condensed text-ui font-bold text-primary">{value}</span>
          {value === defaultValue ? ' · типово' : value === 0 ? ' · не враховувати' : null}
        </span>
      </div>
      <Slider.Root
        value={[value]}
        min={0}
        max={max}
        step={1}
        disabled={disabled}
        onValueChange={([next]) => onValueChange(next ?? 0)}
        aria-labelledby={`${id}-label`}
        aria-describedby={`${id}-hint`}
        className="relative flex h-5 w-full touch-none items-center select-none data-disabled:opacity-50"
      >
        <Slider.Track className="relative h-1 grow overflow-hidden rounded-full bg-sunken ring-1 ring-[var(--border-color-default)] ring-inset">
          <Slider.Range className="absolute h-full rounded-full bg-accent" />
        </Slider.Track>
        <Slider.Thumb
          aria-label={label}
          className="block size-4 rounded-full border-2 border-[var(--border-color-accent)] bg-[var(--fill-surface)] shadow-[0_1px_2px_oklch(0%_0_0/0.2)] transition-transform duration-100 ease-out hover:scale-110 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus motion-reduce:transition-none"
        />
      </Slider.Root>
      <p id={`${id}-hint`} className="text-caption text-muted">
        {hint}
      </p>
    </div>
  );
}
