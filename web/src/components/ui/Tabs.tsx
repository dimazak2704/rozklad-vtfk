import { Tabs as RadixTabs } from 'radix-ui';
import { useLayoutEffect, useRef, type ComponentProps, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Tabs = RadixTabs.Root;

/**
 * Список вкладок з одним індикатором: смужка 3 px під активною вкладкою їде до нової (200 мс).
 * Положення пишемо прямо в стиль; стежимо за data-state вкладок, бо значення живе в <Tabs>.
 */
export function TabsList({ className, children, ...props }: ComponentProps<typeof RadixTabs.List>) {
  const list = useRef<HTMLDivElement | null>(null);
  const bar = useRef<HTMLSpanElement | null>(null);

  useLayoutEffect(() => {
    const root = list.current;
    const indicator = bar.current;
    if (!root || !indicator) return;
    const place = () => {
      const active = root.querySelector<HTMLElement>('[role="tab"][data-state="active"]');
      if (!active) {
        indicator.style.opacity = '0';
        return;
      }
      indicator.style.opacity = '1';
      indicator.style.width = `${active.offsetWidth - 16}px`;
      indicator.style.transform = `translateX(${active.offsetLeft + 8}px)`;
    };
    place();
    const frame = requestAnimationFrame(() => indicator.setAttribute('data-ready', ''));
    const mutations = new MutationObserver(place);
    mutations.observe(root, { subtree: true, attributes: true, attributeFilter: ['data-state'] });
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(place);
    resize?.observe(root);
    return () => {
      cancelAnimationFrame(frame);
      mutations.disconnect();
      resize?.disconnect();
    };
  }, []);

  return (
    <RadixTabs.List
      ref={list}
      className={cn('scrollbar-none relative flex items-stretch overflow-x-auto border-b border-separator', className)}
      {...props}
    >
      {children}
      <span
        ref={bar}
        aria-hidden="true"
        className={cn(
          'pointer-events-none absolute bottom-0 left-0 h-[3px] rounded-t-[2px] bg-accent opacity-0',
          'data-ready:transition-[transform,width] data-ready:duration-200 data-ready:ease-out motion-reduce:transition-none',
        )}
      />
    </RadixTabs.List>
  );
}

export interface TabsTriggerProps extends ComponentProps<typeof RadixTabs.Trigger> {
  /** Лічильник праворуч: «Очікують 3». */
  count?: number;
  icon?: ReactNode;
}

/** Вкладка: неактивна — звичайна вага, активна — напівжирна з синім індикатором знизу. */
export function TabsTrigger({ className, count, icon, children, ...props }: TabsTriggerProps) {
  return (
    <RadixTabs.Trigger
      className={cn(
        'relative inline-flex h-10 shrink-0 items-center gap-2 px-3 text-ui whitespace-nowrap pointer-coarse:h-12',
        'text-secondary transition-[color] duration-100 ease-out hover:text-primary',
        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
        'data-[state=active]:font-semibold data-[state=active]:text-primary disabled:text-disabled',
        className,
      )}
      {...props}
    >
      {icon}
      <span className="inline-grid">
        <span className="col-start-1 row-start-1">{children}</span>
        <span aria-hidden="true" className="invisible col-start-1 row-start-1 font-semibold">
          {children}
        </span>
      </span>
      {count !== undefined ? <span className="text-meta font-normal text-muted figures">{count}</span> : null}
    </RadixTabs.Trigger>
  );
}

export function TabsContent({ className, ...props }: ComponentProps<typeof RadixTabs.Content>) {
  return <RadixTabs.Content className={cn('pt-4 focus-visible:outline-none', className)} {...props} />;
}
