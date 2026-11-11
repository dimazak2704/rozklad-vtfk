import { X } from 'lucide-react';
import { Dialog as RadixDialog } from 'radix-ui';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';
import { useReturnFocus } from './returnFocus';

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;

/** 480 px — підтвердження, 640 px — форма; ширші — лише для таблиць і попереднього перегляду. */
const SIZES = {
  sm: 'sm:max-w-[30rem]',
  md: 'sm:max-w-[40rem]',
  lg: 'sm:max-w-2xl',
  xl: 'sm:max-w-4xl',
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
 * На телефоні (< 640 px) вікно стає шторкою знизу на всю ширину: радіус лише вгорі, підвал з кнопками
 * над безпечною зоною. Ширше — вікно по центру з полями 24 px.
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
      <div className="pointer-events-none fixed inset-0 z-50 grid place-items-end overflow-y-auto pt-6 sm:place-items-center sm:p-6">
        <RadixDialog.Content
          className={cn(
            'anim-dialog pointer-events-auto relative flex max-h-[calc(100dvh-1.5rem)] w-full flex-col overflow-hidden',
            'rounded-t-dialog border-t border-default bg-raised text-primary shadow-dialog focus-visible:outline-none',
            'sm:rounded-dialog sm:border',
            SIZES[size],
            className,
          )}
          {...(description ? {} : { 'aria-describedby': undefined })}
          {...props}
          {...returnFocus}
        >
          {/* на телефоні вікно — шторка знизу: «ручка» 36×4 px, як у всіх шторок застосунку */}
          <div aria-hidden="true" className="mx-auto mt-2 h-1 w-9 shrink-0 rounded-full bg-[var(--border-color-control)] sm:hidden" />
          <div className="flex items-start gap-3 px-5 pt-3 pb-4 sm:px-6 sm:pt-6">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <RadixDialog.Title className="text-title font-semibold text-primary">{title}</RadixDialog.Title>
              {description ? (
                <RadixDialog.Description className="text-ui text-secondary">{description}</RadixDialog.Description>
              ) : null}
            </div>
            {!hideClose ? (
              <RadixDialog.Close asChild>
                {/* Без підказки: вікно ставить сюди фокус при відкритті, а відкрита підказка забирала
                    перший Esc собі, і вікно закривалося лише з другого. Назва лишається в aria-label. */}
                <IconButton
                  label="Закрити"
                  icon={<X aria-hidden="true" className="size-4" />}
                  hideTooltip
                  className="-me-2 -mt-1.5"
                />
              </RadixDialog.Close>
            ) : null}
          </div>
          <div
            className={cn(
              'min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 sm:px-6',
              footer ? 'pb-5 sm:pb-6' : 'pb-[calc(1.25rem+env(safe-area-inset-bottom))] sm:pb-6',
            )}
          >
            {children}
          </div>
          {footer ? (
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-separator px-5 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] sm:px-6 sm:pb-3">
              {footer}
            </div>
          ) : null}
        </RadixDialog.Content>
      </div>
    </RadixDialog.Portal>
  );
}
