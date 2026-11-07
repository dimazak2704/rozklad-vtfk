import { HoverCard as RadixHoverCard, Popover as RadixPopover } from 'radix-ui';
import { useEffect, useRef, useState, type ComponentProps, type PointerEvent, type ReactElement, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

const CARD =
  'z-50 max-w-[min(22rem,calc(100vw-1rem))] rounded-panel border border-default bg-raised p-3.5 text-primary shadow-popover ' +
  'focus-visible:outline-none';

export const HoverCard = RadixHoverCard.Root;
export const HoverCardTrigger = RadixHoverCard.Trigger;

/** Картка при наведенні (лише миша). Для вмісту, потрібного з клавіатури, — HoverDetails. */
export function HoverCardContent({ className, sideOffset = 6, collisionPadding = 8, ...props }: ComponentProps<typeof RadixHoverCard.Content>) {
  return (
    <RadixHoverCard.Portal>
      <RadixHoverCard.Content
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        className={cn('anim-pop', CARD, className)}
        {...props}
      />
    </RadixHoverCard.Portal>
  );
}

export interface HoverDetailsProps {
  /** Елемент-тригер (кнопка або посилання, що приймає ref). */
  children: ReactElement;
  content: ReactNode;
  side?: 'top' | 'right' | 'bottom' | 'left';
  align?: 'start' | 'center' | 'end';
  openDelay?: number;
  closeDelay?: number;
  /** Підпис діалогу для читача екрана. */
  label?: string;
  contentClassName?: string;
}

/**
 * Деталі, доступні всім: мишею — при наведенні (300 мс), з клавіатури й на дотик — по Enter/тапу.
 * Так влаштована картка заняття: повна назва, викладач, аудиторія, що змінилося і чому.
 */
export function HoverDetails({
  children,
  content,
  side = 'bottom',
  align = 'start',
  openDelay = 300,
  closeDelay = 150,
  label,
  contentClassName,
}: HoverDetailsProps) {
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clear = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  const schedule = (next: boolean, delay: number) => {
    clear();
    timer.current = setTimeout(() => setOpen(next), delay);
  };

  useEffect(() => clear, []);

  const hoverHandlers = {
    onPointerEnter: (event: PointerEvent) => {
      if (event.pointerType === 'mouse') schedule(true, openDelay);
    },
    onPointerLeave: (event: PointerEvent) => {
      if (event.pointerType === 'mouse') schedule(false, closeDelay);
    },
  };

  return (
    <RadixPopover.Root
      open={open}
      onOpenChange={(next) => {
        clear();
        setOpen(next);
      }}
    >
      <RadixPopover.Trigger asChild {...hoverHandlers}>
        {children}
      </RadixPopover.Trigger>
      <RadixPopover.Portal>
        <RadixPopover.Content
          side={side}
          align={align}
          sideOffset={6}
          collisionPadding={8}
          aria-label={label}
          onOpenAutoFocus={(event) => event.preventDefault()}
          className={cn('anim-pop', CARD, contentClassName)}
          {...hoverHandlers}
        >
          {content}
        </RadixPopover.Content>
      </RadixPopover.Portal>
    </RadixPopover.Root>
  );
}
