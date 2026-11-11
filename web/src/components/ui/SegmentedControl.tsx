import { ToggleGroup } from 'radix-ui';
import { useLayoutEffect, useRef, type ReactNode } from 'react';
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
  /** Застаріле: колишня синя смуга. Тепер вигляд один скрізь. */
  tone?: 'default' | 'on-shell';
  className?: string;
}

/**
 * Перемикач подання: «День / Тиждень», «Сьогодні / Завтра», «Уся група / 1 п/г». Один вигляд скрізь:
 * доріжка `sunken` з відступом 4 px, вибраний сегмент — поверхня з тонким кільцем. Порожнім бути не може.
 * Один «бігунок» під вибраним сегментом їде до нового вибору (200 мс), а не перефарбовує сегменти.
 * Значення, якого немає серед варіантів, означає «нічого не вибрано» — бігунок ховається.
 */
export function SegmentedControl<V extends string = string>({
  value,
  onValueChange,
  options,
  size = 'md',
  stretch = false,
  className,
  'aria-label': ariaLabel,
}: SegmentedControlProps<V>) {
  const root = useRef<HTMLDivElement | null>(null);
  const thumb = useRef<HTMLSpanElement | null>(null);

  // положення бігунка пишемо прямо в стиль: це вимір DOM, а не стан React
  useLayoutEffect(() => {
    const track = root.current;
    const indicator = thumb.current;
    if (!track || !indicator) return;
    const place = () => {
      const active = track.querySelector<HTMLElement>('[data-state="on"]');
      if (!active) {
        indicator.style.opacity = '0';
        return;
      }
      indicator.style.opacity = '1';
      indicator.style.width = `${active.offsetWidth}px`;
      indicator.style.transform = `translateX(${active.offsetLeft}px)`;
    };
    place();
    // перше розміщення — без анімації, далі бігунок їде
    const frame = requestAnimationFrame(() => indicator.setAttribute('data-ready', ''));
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(place);
    observer?.observe(track);
    return () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [value, options]);

  return (
    <ToggleGroup.Root
      ref={root}
      type="single"
      value={value}
      onValueChange={(next) => {
        // повторний клік по активному сегменту не має скидати вибір
        if (next) onValueChange(next as V);
      }}
      aria-label={ariaLabel}
      className={cn(
        'scrollbar-none relative isolate inline-flex max-w-full items-stretch overflow-x-auto rounded-control p-1',
        // 36 / 32 px; на дотику — 44, щоб сегмент був повноцінною ціллю натискання
        size === 'sm' ? 'h-8 pointer-coarse:h-11' : 'h-9 pointer-coarse:h-11',
        'bg-sunken shadow-[inset_0_0_0_1px_var(--border-color-separator)]',
        stretch ? 'flex w-full' : 'w-fit self-start',
        className,
      )}
    >
      <span
        ref={thumb}
        aria-hidden="true"
        className={cn(
          'pointer-events-none absolute inset-y-1 left-0 -z-10 rounded-[calc(var(--radius-control)-4px)] opacity-0',
          'data-ready:transition-[transform,width] data-ready:duration-200 data-ready:ease-out motion-reduce:transition-none',
          'bg-surface shadow-lift dark:bg-active',
        )}
      />
      {options.map((option) => (
        <ToggleGroup.Item
          key={option.value}
          value={option.value}
          disabled={option.disabled}
          aria-label={option.ariaLabel}
          className={cn(
            'inline-flex items-center justify-center gap-1.5 rounded-[calc(var(--radius-control)-4px)] px-3 whitespace-nowrap',
            'transition-[color] duration-100 ease-out',
            'focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-focus',
            'disabled:cursor-not-allowed disabled:text-disabled',
            size === 'sm' ? 'text-caption font-[550]' : 'text-ui font-[550]',
            stretch && 'flex-1',
            'text-secondary hover:text-primary data-[state=on]:font-[650] data-[state=on]:text-primary',
          )}
        >
          {option.icon}
          {/* невидимий жирний дублікат тримає ширину, щоб сегмент не «стрибав» при виборі */}
          <span className="inline-grid">
            <span className="col-start-1 row-start-1">{option.label}</span>
            <span aria-hidden="true" className="invisible col-start-1 row-start-1 font-[650]">
              {option.label}
            </span>
          </span>
        </ToggleGroup.Item>
      ))}
    </ToggleGroup.Root>
  );
}
