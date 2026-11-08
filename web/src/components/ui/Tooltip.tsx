import { Tooltip as RadixTooltip } from 'radix-ui';
import type { ReactElement, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { KbdCombo } from './Kbd';

/**
 * Один провайдер на застосунок: підказка зʼявляється через 400 мс, а якщо інша була відкрита
 * менш ніж 300 мс тому — одразу (користувач уже «читає» панель).
 */
export function TooltipProvider({ children }: { children: ReactNode }) {
  return (
    <RadixTooltip.Provider delayDuration={400} skipDelayDuration={300}>
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
  className?: string;
}

/** Підказка при наведенні та фокусі. Тригер має бути елементом, що приймає ref (кнопка, посилання). */
export function Tooltip({ content, shortcut, side = 'top', align = 'center', children, disabled, className }: TooltipProps) {
  if (disabled || (!content && !shortcut)) return children;
  return (
    <RadixTooltip.Root>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          align={align}
          sideOffset={6}
          collisionPadding={8}
          className={cn(
            'z-50 flex max-w-72 items-center gap-2 rounded-control bg-inverse px-2.5 py-1.5 text-caption font-medium text-inverse shadow-popover',
            'anim-tooltip origin-(--radix-tooltip-content-transform-origin)',
            className,
          )}
        >
          <span>{content}</span>
          {shortcut ? <KbdCombo keys={shortcut} tone="inverse" /> : null}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}
