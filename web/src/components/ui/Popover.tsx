import { Popover as RadixPopover } from 'radix-ui';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Popover = RadixPopover.Root;
export const PopoverTrigger = RadixPopover.Trigger;
export const PopoverAnchor = RadixPopover.Anchor;
export const PopoverClose = RadixPopover.Close;

export interface PopoverContentProps extends Omit<ComponentProps<typeof RadixPopover.Content>, 'title'> {
  /** Заголовок усередині (необовʼязково). */
  title?: ReactNode;
  /** Ширина: `auto` за вмістом, `trigger` — як у тригера. */
  width?: 'auto' | 'trigger' | 'sm' | 'md' | 'lg';
}

const WIDTHS = {
  auto: '',
  trigger: 'w-(--radix-popover-trigger-width)',
  sm: 'w-64',
  md: 'w-80',
  lg: 'w-[26rem]',
} as const;

/** Спливна панель поруч із тригером: фільтри, «Чому?», вибір дати. Закривається Esc і кліком поза нею. */
export function PopoverContent({
  className,
  title,
  width = 'md',
  sideOffset = 6,
  collisionPadding = 8,
  align = 'start',
  children,
  ...props
}: PopoverContentProps) {
  return (
    <RadixPopover.Portal>
      <RadixPopover.Content
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        align={align}
        className={cn(
          'anim-pop z-50 max-h-(--radix-popover-content-available-height) max-w-[calc(100vw-1rem)] overflow-y-auto overscroll-contain',
          'rounded-panel border border-default bg-raised p-3 text-primary shadow-popover',
          'origin-(--radix-popover-content-transform-origin) focus-visible:outline-none',
          WIDTHS[width],
          className,
        )}
        {...props}
      >
        {title ? <p className="mb-2 text-ui font-semibold">{title}</p> : null}
        {children}
      </RadixPopover.Content>
    </RadixPopover.Portal>
  );
}
