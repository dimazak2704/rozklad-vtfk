import { HoverCard as RadixHoverCard, Popover as RadixPopover } from 'radix-ui';
import { useEffect, useRef, type ComponentProps, type MouseEvent, type PointerEvent, type ReactElement, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { useHoverIntent } from './hover-intent';
import { HOVER_CARD_CLOSE_DELAY_MS, HOVER_CARD_DELAY_MS } from './Tooltip.helpers';

const CARD =
  'z-50 max-w-[min(22rem,calc(100vw-1rem))] rounded-popover border border-default bg-raised p-3.5 text-primary shadow-popover ' +
  'focus-visible:outline-none';

export const HoverCard = RadixHoverCard.Root;
export const HoverCardTrigger = RadixHoverCard.Trigger;

/** Картка при наведенні (лише миша). Для вмісту, потрібного з клавіатури, — HoverDetails. */
export function HoverCardContent({
  className,
  sideOffset = 6,
  collisionPadding = 8,
  instant = false,
  peek = false,
  ...props
}: ComponentProps<typeof RadixHoverCard.Content> & {
  /** Перемикання з картки на картку (`useHoverIntent`): без анімації появи й зникнення. */
  instant?: boolean;
  /**
   * Картка лише для читання, без дій: миша проходить крізь неї до клітинок під нею (interaction.css,
   * `[data-hover-peek]`), тож картка не стає пасткою, коли курсор іде до сусідньої клітинки.
   */
  peek?: boolean;
}) {
  return (
    <RadixHoverCard.Portal>
      <RadixHoverCard.Content
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        data-hover-peek={peek || undefined}
        data-hover-instant={instant || undefined}
        className={cn(!instant && 'anim-pop', CARD, className)}
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
 * Деталі, доступні всім: мишею — при наведенні (550 мс; поки інша картка відкрита чи щойно закрилась —
 * одразу, а та зникає), з клавіатури й на дотик — по Enter/тапу. Так влаштована картка заняття: повна назва,
 * викладач, аудиторія, що змінилося і чому. У картці є посилання, тож на неї можна перейти мишею.
 */
export function HoverDetails({
  children,
  content,
  side = 'bottom',
  align = 'start',
  openDelay = HOVER_CARD_DELAY_MS,
  closeDelay = HOVER_CARD_CLOSE_DELAY_MS,
  label,
  contentClassName,
}: HoverDetailsProps) {
  const intent = useHoverIntent({ openDelay, closeDelay });
  // картку відкрило наведення мишею: при закритті фокус на тригер не повертаємо, інакше Radix
  // ставить його скриптом і :focus-visible малює кільце на клітинці, якої ніхто не натискав
  const openedByHover = useRef(false);
  useEffect(() => {
    if (intent.open) openedByHover.current = intent.byHover;
  }, [intent.open, intent.byHover]);

  const hoverHandlers = {
    onPointerEnter: (event: PointerEvent) => {
      if (event.pointerType === 'mouse') intent.enter();
    },
    onPointerLeave: (event: PointerEvent) => {
      if (event.pointerType === 'mouse') intent.leave();
    },
  };

  return (
    <RadixPopover.Root
      open={intent.open}
      onOpenChange={(next) => {
        // Enter, тап чи клік: фокус після закриття повертається на тригер, як годиться діалогу
        if (next) intent.show();
        else intent.close();
      }}
    >
      <RadixPopover.Trigger
        asChild
        {...hoverHandlers}
        onClick={(event: MouseEvent<HTMLElement>) => {
          // картку вже відкрило наведення (з «розігрітої» сітки — одразу): клік по кнопці означає «відкрий»,
          // а не «закрий», тож лишаємо її відкритою, тепер як діалог (фокус потім повернеться на тригер).
          // Посилання не чіпаємо: клік по ньому — перехід.
          if (intent.open && intent.byHover && !event.currentTarget.closest('a[href]')) {
            event.preventDefault();
            intent.show();
          }
        }}
      >
        {children}
      </RadixPopover.Trigger>
      <RadixPopover.Portal>
        <RadixPopover.Content
          side={side}
          align={align}
          sideOffset={6}
          collisionPadding={8}
          aria-label={label}
          data-hover-instant={intent.instant || undefined}
          onOpenAutoFocus={(event) => event.preventDefault()}
          onCloseAutoFocus={(event) => {
            if (openedByHover.current) event.preventDefault();
            openedByHover.current = false;
          }}
          className={cn(!intent.instant && 'anim-pop', CARD, contentClassName)}
          {...hoverHandlers}
        >
          {content}
        </RadixPopover.Content>
      </RadixPopover.Portal>
    </RadixPopover.Root>
  );
}
