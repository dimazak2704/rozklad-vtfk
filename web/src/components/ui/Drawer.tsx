import { X } from 'lucide-react';
import { createContext, useContext, type ComponentProps, type ReactNode } from 'react';
import { Drawer as Vaul, type DialogProps } from 'vaul';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';
import { useReturnFocus } from './returnFocus';

type Side = 'bottom' | 'right' | 'left';

/** Сторона, з якої шторка виїжджає насправді (на телефоні — завжди знизу). */
const SideContext = createContext<Side | null>(null);

/** Вужче 768 px бічна шторка лишала б смужку сторінки збоку — там усе виїжджає знизу на всю ширину. */
const PHONE = '(max-width: 767.98px)';

/**
 * Корінь шторки. `direction="right"` на планшеті й десктопі — панель 400 px праворуч; на телефоні та сама
 * шторка виїжджає знизу на всю ширину, з «ручкою» і свайпом.
 */
export function Drawer({ direction = 'bottom', ...props }: DialogProps) {
  const phone = useMediaQuery(PHONE);
  const side: Side = phone || direction === 'top' ? 'bottom' : direction;
  return (
    <SideContext.Provider value={side}>
      <Vaul.Root {...props} direction={side} />
    </SideContext.Provider>
  );
}
export const DrawerTrigger = Vaul.Trigger;
export const DrawerClose = Vaul.Close;

export interface DrawerContentProps extends Omit<ComponentProps<typeof Vaul.Content>, 'title'> {
  title: ReactNode;
  description?: ReactNode;
  footer?: ReactNode;
  /** Сторона на ширших екранах (як `direction` у <Drawer>); на телефоні шторка завжди знизу. */
  side?: Side;
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
  side: requestedSide = 'bottom',
  titleClassName,
  hideClose = false,
  className,
  children,
  onOpenAutoFocus,
  onCloseAutoFocus,
  ...props
}: DrawerContentProps) {
  const returnFocus = useReturnFocus(onOpenAutoFocus, onCloseAutoFocus);
  const rootSide = useContext(SideContext);
  const side: Side = rootSide === 'bottom' ? 'bottom' : requestedSide;
  return (
    <Vaul.Portal>
      <Vaul.Overlay className="fixed inset-0 z-50 bg-scrim" />
      <Vaul.Content
        data-side={side}
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
          <div aria-hidden="true" className="mx-auto mt-2 h-1 w-9 shrink-0 rounded-full bg-[var(--border-color-control)]" />
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
