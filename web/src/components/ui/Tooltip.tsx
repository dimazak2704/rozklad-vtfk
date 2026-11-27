import { Tooltip as RadixTooltip } from 'radix-ui';
import { useRef, useState, type ElementType, type ReactElement, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { KbdCombo } from './Kbd';
import { HOVER_DELAY_MS, isTruncated } from './Tooltip.helpers';

/**
 * Один провайдер на застосунок: підказка зʼявляється через 300 мс, а якщо інша була відкрита
 * менш ніж 300 мс тому — одразу (користувач уже «читає» панель). Для щільної сітки дрібних цілей
 * (коди груп) вкладений провайдер з `skipDelayDuration={0}`: прогін мишею по сітці не блимає підказкою
 * на кожному коді.
 */
export function TooltipProvider({ children, skipDelayDuration = 300 }: { children: ReactNode; skipDelayDuration?: number }) {
  return (
    <RadixTooltip.Provider delayDuration={HOVER_DELAY_MS} skipDelayDuration={skipDelayDuration}>
      {children}
    </RadixTooltip.Provider>
  );
}

interface TooltipProps {
  content: ReactNode;
  /** Комбінація клавіш, що показується поруч: `mod+k`. */
  shortcut?: string;
  side?: 'top' | 'right' | 'bottom' | 'left';
  align?: 'start' | 'center' | 'end';
  children: ReactElement;
  /** Не показувати (наприклад, коли підпис уже видно поруч). */
  disabled?: boolean;
  /**
   * Підказка не ловить мишу: зникає, щойно курсор зійшов із тригера, і не перекриває сусідні цілі під собою.
   * Для посилань у картці деталей: підказка праворуч від посилання; біля краю екрана Radix перекидає її
   * на сусідній підпис — курсор має пройти крізь неї, а картка лишається відкритою. Крізь обгортку Radix миша теж проходить (interaction.css).
   */
  passThrough?: boolean;
  className?: string;
}

/** Підказка при наведенні та фокусі. Тригер має бути елементом, що приймає ref (кнопка, посилання). */
export function Tooltip({ content, shortcut, side = 'top', align = 'center', children, disabled, passThrough = false, className }: TooltipProps) {
  if (disabled || (!content && !shortcut)) return children;
  return (
    <RadixTooltip.Root disableHoverableContent={passThrough || undefined}>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          align={align}
          sideOffset={6}
          collisionPadding={8}
          data-pass-through={passThrough || undefined}
          className={cn(
            'z-50 flex max-w-72 items-center gap-2 rounded-control bg-raised px-2 py-1 text-meta text-secondary shadow-lift',
            'anim-tooltip origin-(--radix-tooltip-content-transform-origin)',
            className,
          )}
        >
          <span>{content}</span>
          {shortcut ? <KbdCombo keys={shortcut} /> : null}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}

interface TruncatedTextProps {
  children: ReactNode;
  /** Повне значення для підказки; за замовчуванням — сам текст. */
  full?: ReactNode;
  /** Класи обрізання (`truncate`, `line-clamp-2`) і типографіка — на самому елементі. */
  className?: string;
  as?: ElementType;
  side?: 'top' | 'right' | 'bottom' | 'left';
}

/**
 * Текст, що може обрізатися: при наведенні мишею показує повне значення, але лише коли його справді обрізано.
 * Читач екрана й так чує весь текст (обрізання лише візуальне), тож фокус елемента не потрібен.
 */
export function TruncatedText({ children, full, className, as: Tag = 'span', side = 'top' }: TruncatedTextProps) {
  const ref = useRef<HTMLElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <RadixTooltip.Root open={open} onOpenChange={(next) => setOpen(next && isTruncated(ref.current))}>
      <RadixTooltip.Trigger asChild>
        <Tag ref={ref} className={className}>
          {children}
        </Tag>
      </RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          align="start"
          sideOffset={6}
          collisionPadding={8}
          className="anim-tooltip z-50 max-w-80 origin-(--radix-tooltip-content-transform-origin) rounded-control bg-raised px-2 py-1 text-meta text-secondary shadow-lift"
        >
          {full ?? children}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}
