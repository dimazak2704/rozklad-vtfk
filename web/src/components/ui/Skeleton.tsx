import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

/** Сірий силует вмісту під час завантаження. Форму задає className (висота, ширина, радіус). */
export function Skeleton({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span
      aria-hidden="true"
      className={cn('block rounded-plate bg-skeleton motion-safe:animate-shimmer', className)}
      {...props}
    />
  );
}

interface SkeletonTextProps {
  lines?: number;
  className?: string;
}

/** Кілька рядків тексту різної довжини. */
export function SkeletonText({ lines = 3, className }: SkeletonTextProps) {
  const widths = ['w-full', 'w-11/12', 'w-4/5', 'w-2/3', 'w-3/4'];
  return (
    <span className={cn('flex flex-col gap-2', className)} aria-hidden="true">
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} className={cn('h-3.5', index === lines - 1 ? 'w-1/2' : widths[index % widths.length])} />
      ))}
    </span>
  );
}

/** Обгортка для групи силуетів: одне повідомлення для читача екрана замість десятків порожніх блоків. */
export function LoadingRegion({ label = 'Завантажуємо…', className, children }: { label?: string; className?: string; children: ReactNode }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true" className={className}>
      <span className="sr-only">{label}</span>
      {children}
    </div>
  );
}
