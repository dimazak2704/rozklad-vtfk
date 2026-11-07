import { Tabs as RadixTabs } from 'radix-ui';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Tabs = RadixTabs.Root;

export function TabsList({ className, ...props }: ComponentProps<typeof RadixTabs.List>) {
  return (
    <RadixTabs.List
      className={cn('scrollbar-none flex items-stretch gap-1 overflow-x-auto border-b border-separator', className)}
      {...props}
    />
  );
}

export interface TabsTriggerProps extends ComponentProps<typeof RadixTabs.Trigger> {
  /** Лічильник праворуч: «Очікують 3». */
  count?: number;
  icon?: ReactNode;
}

/** Вкладка з підкресленням маршрутним синім — як активна лінія на схемі. */
export function TabsTrigger({ className, count, icon, children, ...props }: TabsTriggerProps) {
  return (
    <RadixTabs.Trigger
      className={cn(
        'relative -mb-px inline-flex h-10 shrink-0 items-center gap-2 border-b-2 border-transparent px-3 text-ui font-semibold whitespace-nowrap',
        'text-secondary transition-[color,border-color] duration-100 ease-out hover:text-primary',
        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
        'data-[state=active]:border-accent data-[state=active]:text-primary disabled:opacity-50',
        className,
      )}
      {...props}
    >
      {icon}
      {children}
      {count !== undefined ? (
        <span className="min-w-5 rounded-full bg-sunken px-1.5 text-center text-meta font-semibold figures text-secondary">
          {count}
        </span>
      ) : null}
    </RadixTabs.Trigger>
  );
}

export function TabsContent({ className, ...props }: ComponentProps<typeof RadixTabs.Content>) {
  return <RadixTabs.Content className={cn('pt-4 focus-visible:outline-none', className)} {...props} />;
}
