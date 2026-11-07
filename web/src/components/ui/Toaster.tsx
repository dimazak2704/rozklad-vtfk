import { CircleCheck, CircleExclamationPoint, Info, TriangleExclamationPoint } from 'lucide-react';
import { Toaster as Sonner } from 'sonner';
import { useResolvedTheme } from '@/hooks/useResolvedTheme';
import { Spinner } from './Spinner';

/**
 * Сповіщення (sonner) у кольорах системи. Один екземпляр у корені застосунку.
 * Показувати через `notify` з `@/components/ui/toast`.
 */
export function Toaster() {
  const theme = useResolvedTheme();
  return (
    <Sonner
      theme={theme}
      position="bottom-right"
      offset={16}
      mobileOffset={{ bottom: 'calc(var(--tabbar-height) + 12px)', left: 12, right: 12 }}
      visibleToasts={4}
      closeButton={false}
      containerAriaLabel="Сповіщення"
      icons={{
        success: <CircleCheck aria-hidden="true" className="size-4 text-add" />,
        error: <CircleExclamationPoint aria-hidden="true" className="size-4 text-danger" />,
        warning: <TriangleExclamationPoint aria-hidden="true" className="size-4 text-change" />,
        info: <Info aria-hidden="true" className="size-4 text-accent" />,
        loading: <Spinner size="sm" className="text-muted" />,
      }}
      toastOptions={{
        unstyled: true,
        classNames: {
          toast:
            'group flex w-full items-start gap-3 rounded-panel border border-default bg-raised p-3.5 text-primary shadow-popover sm:w-[22rem]',
          title: 'text-ui font-semibold',
          description: 'mt-0.5 text-caption text-secondary',
          icon: 'mt-0.5 flex size-4 shrink-0 items-center justify-center',
          content: 'flex min-w-0 flex-1 flex-col',
          actionButton:
            'ms-auto shrink-0 self-center rounded-control bg-accent px-3 py-1.5 text-caption font-semibold text-on-accent hover:bg-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          cancelButton:
            'shrink-0 self-center rounded-control px-2 py-1.5 text-caption font-semibold text-secondary hover:bg-hover',
        },
      }}
    />
  );
}
