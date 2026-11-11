import { X } from 'lucide-react';
import type { ComponentProps, ReactNode } from 'react';
import { Drawer as Vaul } from 'vaul';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';
import { useReturnFocus } from './returnFocus';

export const Drawer = Vaul.Root;
export const DrawerTrigger = Vaul.Trigger;
export const DrawerClose = Vaul.Close;

export interface DrawerContentProps extends Omit<ComponentProps<typeof Vaul.Content>, 'title'> {
  title: ReactNode;
  description?: ReactNode;
  footer?: ReactNode;
  /** Сторона, з якої виїжджає (має збігатися з `direction` у <Drawer>). */
  side?: 'bottom' | 'right' | 'left';
  /** Клас заголовка: `sr-only`, коли вміст має власний видимий заголовок. */
  titleClassName?: string;
  /** Сховати кнопку «Закрити». За замовчуванням вона є завжди: свайп і Esc знає не кожен. */
  hideClose?: boolean;
}

/**
 * Шторка: на телефоні — знизу з «ручкою» і свайпом, на десктопі — збоку для деталей.
 * Завжди має видиму кнопку «Закрити»: на вузькому екрані шторка на всю ширину, і тапнути повз неї ніде.
 * `<Drawer direction="right"><DrawerContent side="right" title="…">…</DrawerContent></Drawer>`
 */
export function DrawerContent({
  title,
  description,
  footer,
  side = 'bottom',
  titleClassName,
  hideClose = false,
  className,
  children,
  onOpenAutoFocus,
  onCloseAutoFocus,
  ...props
}: DrawerContentProps) {
  const returnFocus = useReturnFocus(onOpenAutoFocus, onCloseAutoFocus);
  return (
    <Vaul.Portal>
      <Vaul.Overlay className="fixed inset-0 z-50 bg-scrim" />
      <Vaul.Content
        className={cn(
          'fixed z-50 flex flex-col border-default bg-raised text-primary shadow-dialog focus-visible:outline-none',
          side === 'bottom' && 'inset-x-0 bottom-0 max-h-[85dvh] rounded-t-dialog border-t pb-[env(safe-area-inset-bottom)]',
          side === 'right' && 'inset-y-0 end-0 w-full max-w-[min(25rem,100vw)] border-s sm:rounded-s-dialog',
          side === 'left' && 'inset-y-0 start-0 w-full max-w-xs border-e',
          className,
        )}
        {...props}
        {...returnFocus}
      >
        {side === 'bottom' ? (
          <div aria-hidden="true" className="mx-auto mt-2 h-1 w-8 shrink-0 rounded-full bg-[var(--border-color-control)]" />
        ) : null}
        <div className="flex items-start gap-3 px-4 pt-3 pb-3 sm:px-5 sm:pt-4">
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <Vaul.Title className={cn('text-title font-semibold', titleClassName)}>{title}</Vaul.Title>
            {description ? <Vaul.Description className="text-ui text-secondary">{description}</Vaul.Description> : null}
          </div>
          {!hideClose ? (
            <Vaul.Close asChild>
              <IconButton label="Закрити" icon={<X aria-hidden="true" className="size-4" />} hideTooltip className="-me-2 -mt-1" />
            </Vaul.Close>
          ) : null}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-5 sm:px-5">{children}</div>
        {footer ? (
          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-separator px-5 py-3">{footer}</div>
        ) : null}
      </Vaul.Content>
    </Vaul.Portal>
  );
}
