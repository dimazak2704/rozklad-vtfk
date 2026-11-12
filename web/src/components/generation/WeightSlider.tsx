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

/**
 * Вага одного мʼякого обмеження: повзунок 0…max і число поруч. Пояснення — для читача екрана тут,
 * для очей — у спільній розгортці «Що означають ваги?». 0 — не враховувати.
 */
export function WeightSlider({ label, hint, value, max, defaultValue, onValueChange, disabled }: WeightSliderProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <span id={`${id}-label`} className="text-ui font-[550] text-primary">
          {label}
        </span>
        {/* типове значення нічого не каже — підпис лише біля зміненого: «змінено · скинути» */}
        <span className="flex items-baseline gap-1 text-caption text-muted figures">
          <span className="text-ui font-semibold text-primary">{value}</span>
          {value === 0 ? <span>· не враховувати</span> : null}
          {value !== defaultValue ? (
            <>
              {value === 0 ? null : <span>· змінено</span>}
              <span aria-hidden="true">·</span>
              <button
                type="button"
                onClick={() => onValueChange(defaultValue)}
                disabled={disabled}
                aria-label={`Скинути «${label}» до типового ${defaultValue}`}
                className="rounded-tag font-[650] text-accent hover-fine:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:text-disabled pointer-coarse:min-h-11"
              >
                скинути
              </button>
            </>
          ) : null}
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
        className="relative flex h-5 w-full touch-none items-center select-none data-disabled:opacity-50 pointer-coarse:h-11"
      >
        <Slider.Track className="relative h-1 grow overflow-hidden rounded-full bg-sunken ring-1 ring-[var(--border-color-default)] ring-inset">
          <Slider.Range className="absolute h-full rounded-full bg-accent" />
        </Slider.Track>
        <Slider.Thumb
          aria-label={label}
          className="relative block size-4 rounded-full border-2 before:absolute before:-inset-3.5 before:content-[''] pointer-coarse:size-6 border-[var(--border-color-accent)] bg-[var(--fill-surface)] shadow-[0_1px_2px_oklch(0%_0_0/0.2)] transition-transform duration-100 ease-out hover-fine:scale-110 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus motion-reduce:transition-none"
        />
      </Slider.Root>
      <span id={`${id}-hint`} className="sr-only">
        {hint}
      </span>
    </div>
  );
}
