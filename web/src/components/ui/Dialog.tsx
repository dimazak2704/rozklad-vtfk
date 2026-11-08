import { X } from 'lucide-react';
import { Dialog as RadixDialog } from 'radix-ui';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';
import { useReturnFocus } from './returnFocus';

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;

const SIZES = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
} as const;

export interface DialogContentProps extends Omit<ComponentProps<typeof RadixDialog.Content>, 'title'> {
  title: ReactNode;
  /** Пояснення під заголовком; стає aria-describedby. */
  description?: ReactNode;
  /** Кнопки внизу (праворуч). Головна дія — остання. */
  footer?: ReactNode;
  size?: keyof typeof SIZES;
  /** Сховати хрестик (коли закриття лише через кнопки футера). */
  hideClose?: boolean;
}

/**
 * Модальне вікно. Використовуйте лише коли дія потребує повної уваги (підтвердження, форма, що
 * не вміщається поруч). Фокус утримується всередині, Esc закриває, фон блокується; після закриття
 * фокус повертається на елемент, з якого вікно відкрили (див. `useReturnFocus`).
 */
export function DialogContent({
  title,
  description,
  footer,
  size = 'md',
  hideClose = false,
  className,
  children,
  onOpenAutoFocus,
  onCloseAutoFocus,
  ...props
}: DialogContentProps) {
  const returnFocus = useReturnFocus(onOpenAutoFocus, onCloseAutoFocus);
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim" />
      <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center overflow-y-auto p-3 sm:p-6">
        <RadixDialog.Content
          className={cn(
            'anim-dialog pointer-events-auto relative flex max-h-[calc(100dvh-1.5rem)] w-full flex-col overflow-hidden',
            'rounded-dialog border border-default bg-raised text-primary shadow-dialog focus-visible:outline-none',
            SIZES[size],
            className,
          )}
          {...(description ? {} : { 'aria-describedby': undefined })}
          {...props}
          {...returnFocus}
        >
          <div className="flex items-start gap-3 px-5 pt-5 pb-4">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <RadixDialog.Title className="text-heading font-semibold text-primary">{title}</RadixDialog.Title>
              {description ? (
                <RadixDialog.Description className="text-ui text-secondary">{description}</RadixDialog.Description>
              ) : null}
            </div>
            {!hideClose ? (
              <RadixDialog.Close asChild>
                <IconButton label="Закрити" icon={<X aria-hidden="true" className="size-4" />} size="sm" className="-me-1.5 -mt-1" />
              </RadixDialog.Close>
            ) : null}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pb-5">{children}</div>
          {footer ? (
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-separator px-5 py-3">
              {footer}
            </div>
          ) : null}
        </RadixDialog.Content>
      </div>
    </RadixDialog.Portal>
  );
}
